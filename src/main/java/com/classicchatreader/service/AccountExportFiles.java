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
 *   <li>they live in one managed, owner-only directory that is emptied at startup (a process killed
 *       mid-download leaves nothing behind across restarts) and swept of stale files on every export;</li>
 *   <li>each file is created owner-only and verified, failing closed when that cannot be enforced;</li>
 *   <li>a {@link Lease} is held from preparation until the download stream closes: at most one per
 *       account and {@value #MAX_CONCURRENT} overall. Leases expire after {@link #LEASE_TTL} so a
 *       lost close cannot lock an account out forever.</li>
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

    private final Path directory;
    private final Clock clock;
    private final Map<String, Lease> leases = new HashMap<>();

    @Autowired
    public AccountExportFiles() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "ccr-account-exports"), Clock.systemUTC());
    }

    AccountExportFiles(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    /** No download can be in progress at startup, so every file left here is an orphan. */
    @PostConstruct
    void removeOrphansFromEarlierRuns() throws IOException {
        ensureDirectory();
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, PREFIX + "*")) {
            for (Path file : files) {
                if (Files.deleteIfExists(file)) removed++;
            }
        }
        if (removed > 0) {
            log.warn("account_export removed {} orphaned export file(s) at startup", removed);
        }
    }

    public synchronized Lease acquire(String userId) throws IOException {
        Instant now = clock.instant();
        // Expired leases (a close that never came) are reclaimed along with their files.
        leases.values().removeIf(lease -> {
            if (lease.startedAt.plus(LEASE_TTL).isBefore(now)) {
                lease.deleteFile();
                return true;
            }
            return false;
        });
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

    private void ensureDirectory() throws IOException {
        if (Files.isDirectory(directory)) return;
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (!Files.getPosixFilePermissions(directory).equals(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))) {
                throw new AccessDeniedException(directory.toString(), null, "export directory is not owner-only");
            }
        } else {
            Files.createDirectories(directory);
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
        private final Instant startedAt;
        private Path file;
        private boolean closed;

        private Lease(String userId, Instant startedAt) {
            this.userId = userId;
            this.startedAt = startedAt;
        }

        public Path createFile() throws IOException {
            if (file != null) throw new IllegalStateException("Export file already created");
            ensureDirectory();
            file = privateFile(directory.getFileSystem().supportedFileAttributeViews(), directory);
            return file;
        }

        /** The download body. Closing it (end of response or client disconnect) ends the lease. */
        public InputStream openForDownload() throws IOException {
            return new FilterInputStream(Files.newInputStream(file)) {
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

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            deleteFile();
            release(this);
        }
    }
}
