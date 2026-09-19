package com.classicchatreader.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Temporary "Download my data" files (BL-043.6). They hold student records, so:
 * <ul>
 *   <li>they live in an owner-only per-process directory under one owner-only root; at startup this
 *       instance's directory and those of dead processes are cleared (a process killed
 *       mid-download leaves nothing behind across restarts) and swept of stale files on every export;</li>
 *   <li>each file is created owner-only and verified, failing closed when that cannot be enforced;</li>
 *   <li>a {@link Lease} is held from preparation until the download stream closes: at most one per
 *       account and {@value #MAX_CONCURRENT} overall. A lease with no activity (preparation or bytes
 *       read by the download) for {@link #LEASE_TTL} is abandoned and reclaimed, so a lost close
 *       cannot lock an account out forever, while a slow download that is still reading keeps it.</li>
 * </ul>
 */
@Component
public class AccountExportFiles {

    private static final Logger log = LoggerFactory.getLogger(AccountExportFiles.class);
    static final int MAX_CONCURRENT = 2;
    static final Duration LEASE_TTL = Duration.ofHours(1);
    private static final String PREFIX = "account-export-";

    /** Thrown when this account already has an export open or the server is at its limit. */
    public static class ExportBusyException extends RuntimeException {
        public ExportBusyException(String message) {
            super(message);
        }
    }

    private static final String INSTANCE_PREFIX = "pid-";

    /** Shared, owner-only root; each JVM works only in its own {@code pid-<n>} subdirectory. */
    private final Path root;
    private final Path directory;
    private final long pid;
    private final java.util.function.LongPredicate processAlive;
    private final Clock clock;
    private final Map<String, Lease> leases = new HashMap<>();

    @Autowired
    public AccountExportFiles() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "ccr-account-exports"), Clock.systemUTC(),
                ProcessHandle.current().pid(), other -> ProcessHandle.of(other).map(ProcessHandle::isAlive).orElse(false));
    }

    AccountExportFiles(Path root, Clock clock) {
        this(root, clock, ProcessHandle.current().pid(), other -> ProcessHandle.of(other).map(ProcessHandle::isAlive).orElse(false));
    }

    AccountExportFiles(Path root, Clock clock, long pid, java.util.function.LongPredicate processAlive) {
        this.root = root;
        this.directory = root.resolve(INSTANCE_PREFIX + pid);
        this.pid = pid;
        this.processAlive = processAlive;
        this.clock = clock;
    }

    /**
     * Startup cleanup that never touches another live instance's exports (JVMs sharing the same OS
     * account and temp dir, e.g. during a rolling start). This instance's own directory cannot hold a
     * live download yet, so it is emptied; another instance's directory is removed only when its
     * process is no longer running.
     */
    @PostConstruct
    void removeOrphansFromEarlierRuns() throws IOException {
        ensureDirectory();
        int removed = 0;
        try (DirectoryStream<Path> instances = Files.newDirectoryStream(root, INSTANCE_PREFIX + "*")) {
            for (Path instance : instances) {
                long owner;
                try {
                    owner = Long.parseLong(instance.getFileName().toString().substring(INSTANCE_PREFIX.length()));
                } catch (NumberFormatException notOurs) {
                    continue;
                }
                if (owner != pid && processAlive.test(owner)) continue;
                if (!Files.isDirectory(instance, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(instance, PREFIX + "*")) {
                    for (Path file : files) {
                        if (Files.deleteIfExists(file)) removed++;
                    }
                }
                if (owner != pid) {
                    try {
                        Files.deleteIfExists(instance);
                    } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                        // Unknown content: leave it rather than guess.
                    }
                }
            }
        }
        if (removed > 0) {
            log.warn("account_export removed {} orphaned export file(s) at startup", removed);
        }
    }

    public synchronized Lease acquire(String userId) throws IOException {
        Instant now = clock.instant();
        // Only abandoned leases are reclaimed: no preparation or download activity for LEASE_TTL
        // (a close that never came). A slow download that is still reading keeps its lease.
        leases.values().removeIf(lease -> lease.reclaimIfIdle(now));
        sweepStaleFiles(now);
        if (leases.containsKey(userId)) {
            throw new ExportBusyException("Your data export is already being prepared or downloaded. Try again when it finishes.");
        }
        if (leases.size() >= MAX_CONCURRENT) {
            throw new ExportBusyException("Data exports are busy right now. Try again in a minute.");
        }
        Lease lease = new Lease(userId, now);
        leases.put(userId, lease);
        return lease;
    }

    private synchronized void release(Lease lease) {
        leases.remove(lease.userId, lease);
    }

    private void sweepStaleFiles(Instant now) throws IOException {
        ensureDirectory();
        Set<Path> live = new java.util.HashSet<>();
        leases.values().forEach(lease -> {
            if (lease.file != null) live.add(lease.file);
        });
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, PREFIX + "*")) {
            for (Path file : files) {
                if (!live.contains(file) && Files.getLastModifiedTime(file).toInstant().plus(LEASE_TTL).isBefore(now)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /**
     * Called before every use, not only at creation: an existing directory is trusted only if it is a
     * real directory (not a symlink), owned by this process's user, and its permissions can be reset
     * to owner-only and read back (POSIX {@code rwx------}, or an owner-only ACL). Otherwise exports
     * are refused, since another local user could swap a file between preparation and download.
     */
    private void ensureDirectory() throws IOException {
        secure(root);
        secure(directory);
    }

    private static void secure(Path directory) throws IOException {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                // Created owner-only from the start: no umask window before the reset below.
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(directory);
            }
        }
        if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new AccessDeniedException(directory.toString(), null, "export directory is not a real directory");
        }
        var owner = Files.getOwner(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!owner.getName().equals(System.getProperty("user.name"))
                && !owner.getName().endsWith("\\" + System.getProperty("user.name"))) {
            throw new AccessDeniedException(directory.toString(), null, "export directory is owned by " + owner.getName());
        }
        Set<String> views = directory.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            var ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(directory, ownerOnly);
            if (!Files.getPosixFilePermissions(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(ownerOnly)) {
                throw new AccessDeniedException(directory.toString(), null, "export directory is not owner-only");
            }
        } else if (views.contains("acl")) {
            var view = Files.getFileAttributeView(directory, AclFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            var ownerOnly = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
            view.setAcl(ownerOnly);
            if (!view.getAcl().equals(ownerOnly)) {
                throw new AccessDeniedException(directory.toString(), null, "export directory is not owner-only");
            }
        } else {
            throw new AccessDeniedException(directory.toString(), null, "cannot restrict the export directory to its owner");
        }
    }

    /**
     * Owner-only file, verified after creation. POSIX: {@code rw-------}. ACL filesystems (Windows):
     * a single owner ACL entry. Fails closed: if neither can be enforced and verified, no file is
     * created and the export is refused.
     */
    static Path privateFile(Set<String> supportedViews, Path dir) throws IOException {
        if (supportedViews.contains("posix")) {
            var ownerOnly = PosixFilePermissions.fromString("rw-------");
            Path file = Files.createTempFile(dir, PREFIX, ".json", PosixFilePermissions.asFileAttribute(ownerOnly));
            if (!Files.getPosixFilePermissions(file).equals(ownerOnly)) {
                Files.deleteIfExists(file);
                throw new IOException("Could not restrict the export file to its owner");
            }
            return file;
        }
        if (supportedViews.contains("acl")) {
            Path file = Files.createTempFile(dir, PREFIX, ".json");
            try {
                var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
                var ownerOnly = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
                view.setAcl(ownerOnly);
                if (!view.getAcl().equals(ownerOnly)) {
                    throw new IOException("Could not restrict the export file to its owner");
                }
                return file;
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(file);
                throw e instanceof IOException io ? io : new IOException("Could not restrict the export file to its owner", e);
            }
        }
        throw new IOException("This filesystem cannot restrict export files to their owner; refusing to write student data");
    }

    /**
     * One export from preparation to the end of its download. {@link #close()} (or closing the
     * download stream) deletes the file and frees the slot; it is idempotent.
     */
    public final class Lease implements AutoCloseable {
        private final String userId;
        private volatile Instant lastActivity;
        private Path file;
        private boolean closed;

        private Lease(String userId, Instant startedAt) {
            this.userId = userId;
            this.lastActivity = startedAt;
        }

        private synchronized void touch() {
            lastActivity = clock.instant();
        }

        /** Atomic with {@link #touch()}: an idle check and its deletion cannot interleave with new activity. */
        private synchronized boolean reclaimIfIdle(Instant now) {
            if (!lastActivity.plus(LEASE_TTL).isBefore(now)) return false;
            closed = true;
            deleteFile();
            return true;
        }

        /** Where the export is written. Every write is a heartbeat, so a long preparation is never "idle". */
        public java.io.OutputStream openForWriting() throws IOException {
            touch();
            return new java.io.FilterOutputStream(Files.newOutputStream(file)) {
                @Override
                public void write(int b) throws IOException {
                    touch();
                    out.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    touch();
                    out.write(b, off, len);
                }
            };
        }

        public Path createFile() throws IOException {
            if (file != null) throw new IllegalStateException("Export file already created");
            ensureDirectory();
            file = privateFile(directory.getFileSystem().supportedFileAttributeViews(), directory);
            touch();
            return file;
        }

        /** The download body. Closing it (end of response or client disconnect) ends the lease. */
        public InputStream openForDownload() throws IOException {
            touch();
            return new FilterInputStream(Files.newInputStream(file)) {
                @Override
                public int read() throws IOException {
                    touch();
                    return super.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    touch();
                    return super.read(b, off, len);
                }

                @Override
                public long skip(long n) throws IOException {
                    touch();
                    return super.skip(n);
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        Lease.this.close();
                    }
                }
            };
        }

        private void deleteFile() {
            if (file == null) return;
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("account_export could not delete {}; the next sweep will retry", file.getFileName(), e);
            }
        }

        /**
         * Lock order is always registry, then lease ({@code acquire} → {@code reclaimIfIdle}). Close
         * therefore finishes with the lease lock before touching the registry, never holding both.
         */
        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                deleteFile();
            }
            release(this);
        }
    }
}
