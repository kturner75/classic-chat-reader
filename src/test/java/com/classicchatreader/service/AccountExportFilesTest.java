package com.classicchatreader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AccountExportFilesTest {

    @TempDir Path tmp;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-19T20:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private AccountExportFiles current;

    private AccountExportFiles files() throws Exception {
        current = new AccountExportFiles(tmp.resolve("exports"), clock);
        current.removeOrphansFromEarlierRuns();
        return current;
    }

    @org.junit.jupiter.api.AfterEach
    void shutdown() throws Exception {
        if (current != null) current.shutdown();
    }

    @Test
    void theLeaseLastsUntilTheDownloadStreamClosesThenDeletesTheFile() throws Exception {
        AccountExportFiles files = files();
        AccountExportFiles.Lease lease = files.acquire("alex");
        Path file = lease.createFile();
        Files.writeString(file, "{}");
        InputStream download = lease.openForDownload();

        assertThrows(AccountExportFiles.ExportBusyException.class, () -> files.acquire("alex"),
                "an open download still counts against the account");
        download.readAllBytes();
        download.close();

        assertFalse(Files.exists(file));
        files.acquire("alex").close();
    }

    @Test
    void atMostTwoExportsAtOnceAcrossAccounts() throws Exception {
        AccountExportFiles files = files();
        AccountExportFiles.Lease a = files.acquire("a");
        AccountExportFiles.Lease b = files.acquire("b");
        assertThrows(AccountExportFiles.ExportBusyException.class, () -> files.acquire("c"));
        a.close();
        a.close(); // idempotent: does not free a second slot
        files.acquire("c");
        assertThrows(AccountExportFiles.ExportBusyException.class, () -> files.acquire("d"));
        b.close();
    }

    @Test
    void closingDownloadsWhileOthersAcquireNeverDeadlocks() throws Exception {
        AccountExportFiles files = files();
        java.util.concurrent.BlockingQueue<AccountExportFiles.Lease> open = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger acquired = new java.util.concurrent.atomic.AtomicInteger();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> {
            Thread closer = new Thread(() -> {
                while (!done.get() || !open.isEmpty()) {
                    AccountExportFiles.Lease lease = open.poll();
                    if (lease != null) lease.close();
                }
            });
            closer.start();
            for (int i = 0; i < 20_000; i++) {
                try {
                    // The idle check in acquire() takes each open lease's lock while the closer thread closes them.
                    now.set(now.get().plusMillis(1));
                    open.add(files.acquire("user-" + (i % 2)));
                    acquired.incrementAndGet();
                } catch (AccountExportFiles.ExportBusyException busy) {
                    Thread.onSpinWait();
                }
            }
            done.set(true);
            closer.join();
        });
        assertTrue(acquired.get() > 0);
    }

    @Test
    void expiredLeasesAreReclaimedWithTheirFiles() throws Exception {
        AccountExportFiles files = files();
        AccountExportFiles.Lease lost = files.acquire("alex");
        Path file = lost.createFile();
        now.set(now.get().plus(AccountExportFiles.LEASE_TTL).plusSeconds(1));
        files.acquire("alex").close();
        assertFalse(Files.exists(file), "a lease whose close never came is reclaimed after the TTL");
    }

    @Test
    void aSlowDownloadThatIsStillReadingKeepsItsLeasePastTheTtl() throws Exception {
        AccountExportFiles files = files();
        AccountExportFiles.Lease lease = files.acquire("alex");
        Path file = lease.createFile();
        Files.writeString(file, "x".repeat(100));
        InputStream download = lease.openForDownload();

        now.set(now.get().plus(AccountExportFiles.LEASE_TTL).minusSeconds(60));
        assertEquals('x', download.read(), "a byte read keeps the lease active");
        now.set(now.get().plus(java.time.Duration.ofMinutes(20)));
        assertThrows(AccountExportFiles.ExportBusyException.class, () -> files.acquire("alex"),
                "past the TTL since start, but read 20 minutes ago: still active, still counted");
        assertTrue(Files.exists(file));

        download.close();
        files.acquire("alex").close();
        assertFalse(Files.exists(file), "closing the download ends the lease and deletes the file");
    }

    @Test
    void aStalledDownloadStaysCountedUntilItsStreamClosesOrTheHardCap() throws Exception {
        AccountExportFiles files = files();
        AccountExportFiles.Lease lease = files.acquire("alex");
        Path file = lease.createFile();
        Files.writeString(file, "x".repeat(100));
        InputStream download = lease.openForDownload();

        // The client stops accepting bytes: no reads for hours, but the response is still live.
        now.set(now.get().plus(AccountExportFiles.LEASE_TTL.multipliedBy(5)));
        assertThrows(AccountExportFiles.ExportBusyException.class, () -> files.acquire("alex"),
                "an open download is never reclaimed as idle, so it keeps counting against the limits");
        assertTrue(Files.exists(file));

        now.set(now.get().plus(AccountExportFiles.MAX_DOWNLOAD_AGE));
        files.acquire("alex").close();
        assertFalse(Files.exists(file), "past the hard cap the lease is reclaimed");
        assertThrows(java.io.IOException.class, download::read, "and its file stream is closed, so the response fails");
    }

    @Test
    void orphansAreRemovedAtStartupAndStaleFilesAreSwept() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("exports"));
        Path previousRun = Files.createDirectories(root.resolve("run-previous"));
        Path orphan = Files.writeString(previousRun.resolve("account-export-orphan.json"), "student data");
        AccountExportFiles files = files();
        assertFalse(Files.exists(orphan), "startup removes files left by a run with no live lock");
        assertFalse(Files.exists(previousRun));

        Path stale = Files.writeString(files.directory().resolve("account-export-stale.json"), "student data");
        Files.setLastModifiedTime(stale, FileTime.from(now.get().minus(AccountExportFiles.LEASE_TTL).minusSeconds(60)));
        Path fresh = Files.writeString(files.directory().resolve("account-export-fresh.json"), "student data");
        Files.setLastModifiedTime(fresh, FileTime.from(now.get()));
        files.acquire("alex").close();
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "recent files may belong to a download in another request");
    }

    @Test
    void startupLeavesAnotherLiveRunsExportsAloneAndClearsRunsWhoseOwnerIsGone() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("exports"));
        Path live = Files.createDirectories(root.resolve("run-live"));
        Path liveFile = Files.writeString(live.resolve("account-export-live.json"), "being generated by another instance");
        Path dead = Files.createDirectories(root.resolve("run-dead"));
        Files.writeString(dead.resolve("account-export-dead.json"), "left by a killed instance");
        Path deadLock = Files.createFile(root.resolve("run-dead.lock"));
        // A running instance holds its lock; the OS released the dead one's when it died,
        // whatever process now has its old PID.
        try (var liveChannel = java.nio.channels.FileChannel.open(Files.createFile(root.resolve("run-live.lock")),
                java.nio.file.StandardOpenOption.WRITE);
             var liveLock = liveChannel.lock()) {
            files();
            assertTrue(Files.exists(liveFile), "a rolling start must not delete another running instance's export");
        }
        assertFalse(Files.exists(dead), "a run whose lock is free is gone, and its directory is removed");
        assertFalse(Files.exists(deadLock));
    }

    @Test
    void gracefulShutdownRemovesThisRunsFilesAndLock() throws Exception {
        AccountExportFiles files = files();
        Path dir = files.directory();
        try (AccountExportFiles.Lease lease = files.acquire("alex")) {
            lease.createFile();
        }
        Files.writeString(dir.resolve("account-export-left.json"), "x");
        files.shutdown();
        current = null;
        assertFalse(Files.exists(dir));
        try (var left = Files.list(tmp.resolve("exports"))) {
            assertEquals(0, left.count(), "no directory or lock file remains after a clean shutdown");
        }
    }

    @Test
    void aLongPreparationThatKeepsWritingIsNeverReclaimed() throws Exception {
        AccountExportFiles files = files();
        AccountExportFiles.Lease lease = files.acquire("alex");
        Path file = lease.createFile();
        try (var out = lease.openForWriting()) {
            for (int minute = 0; minute < 150; minute += 30) {
                now.set(now.get().plus(java.time.Duration.ofMinutes(30)));
                out.write('x');
                assertThrows(AccountExportFiles.ExportBusyException.class, () -> files.acquire("alex"),
                        "still writing at minute " + minute + ": the preparation is active");
            }
        }
        assertTrue(Files.exists(file));
        lease.close();
    }

    @Test
    void aPreExistingLooseDirectoryIsLockedDownAndASymlinkIsRefused() throws Exception {
        Path dir = tmp.resolve("exports");
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        AccountExportFiles files = files();
        Files.setPosixFilePermissions(files.directory(), PosixFilePermissions.fromString("rwxrwxrwx"));
        files.acquire("alex").close();
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)),
                "an existing world-writable root is reset to owner-only before use");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(files.directory())),
                "and so is this instance's directory");

        Path real = Files.createDirectories(tmp.resolve("elsewhere"));
        Path link = Files.createSymbolicLink(tmp.resolve("linked-exports"), real);
        AccountExportFiles viaLink = new AccountExportFiles(link, clock);
        assertThrows(java.nio.file.AccessDeniedException.class, viaLink::removeOrphansFromEarlierRuns);
        assertThrows(java.nio.file.AccessDeniedException.class, () -> viaLink.acquire("alex"));
    }

    @Test
    void directoryAndFilesAreOwnerOnlyAndPermissionsFailClosed() throws Exception {
        AccountExportFiles files = files();
        Path dir = files.directory();
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
            try (AccountExportFiles.Lease lease = files.acquire("alex")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(lease.createFile())));
            }
        }
        java.io.IOException refused = assertThrows(java.io.IOException.class, () -> AccountExportFiles.privateFile(Set.of("basic"), tmp));
        assertTrue(refused.getMessage().contains("refusing to write student data"));
    }
}
