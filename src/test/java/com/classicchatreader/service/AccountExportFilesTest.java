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

    private static final long SELF = 100;
    private final java.util.Set<Long> alive = new java.util.HashSet<>(java.util.Set.of(SELF));

    private AccountExportFiles files() throws Exception {
        AccountExportFiles files = new AccountExportFiles(tmp.resolve("exports"), clock, SELF, alive::contains);
        files.removeOrphansFromEarlierRuns();
        return files;
    }

    private Path own() {
        return tmp.resolve("exports").resolve("pid-" + SELF);
    }

    private long fileCount() throws Exception {
        try (var list = Files.list(own())) {
            return list.count();
        }
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

        now.set(now.get().plus(AccountExportFiles.LEASE_TTL).plusSeconds(1));
        files.acquire("alex").close();
        assertFalse(Files.exists(file), "no reads for the whole TTL: abandoned and reclaimed");
    }

    @Test
    void orphansAreRemovedAtStartupAndStaleFilesAreSwept() throws Exception {
        Path dir = own();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("account-export-orphan.json"), "student data");
        AccountExportFiles files = files();
        assertEquals(0, fileCount(), "startup removes files left by a killed process");

        Path stale = Files.writeString(dir.resolve("account-export-stale.json"), "student data");
        Files.setLastModifiedTime(stale, FileTime.from(now.get().minus(AccountExportFiles.LEASE_TTL).minusSeconds(60)));
        Path fresh = Files.writeString(dir.resolve("account-export-fresh.json"), "student data");
        Files.setLastModifiedTime(fresh, FileTime.from(now.get()));
        files.acquire("alex").close();
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "recent files may belong to a download in another request");
    }

    @Test
    void startupLeavesAnotherLiveInstancesExportsAloneAndClearsDeadOnes() throws Exception {
        Path live = Files.createDirectories(tmp.resolve("exports").resolve("pid-200"));
        Path dead = Files.createDirectories(tmp.resolve("exports").resolve("pid-300"));
        Path liveFile = Files.writeString(live.resolve("account-export-live.json"), "being generated by pid 200");
        Files.writeString(dead.resolve("account-export-dead.json"), "left by a killed pid 300");
        alive.add(200L);

        files();

        assertTrue(Files.exists(liveFile), "a rolling start must not delete another running instance's export");
        assertFalse(Files.exists(dead), "a dead instance's directory is removed");
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
        Files.createDirectories(own());
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.setPosixFilePermissions(own(), PosixFilePermissions.fromString("rwxrwxrwx"));
        AccountExportFiles files = files();
        files.acquire("alex").close();
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)),
                "an existing world-writable root is reset to owner-only before use");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(own())),
                "and so is this instance's directory");

        Path real = Files.createDirectories(tmp.resolve("elsewhere"));
        Path link = Files.createSymbolicLink(tmp.resolve("linked-exports"), real);
        AccountExportFiles viaLink = new AccountExportFiles(link, clock, SELF, alive::contains);
        assertThrows(java.nio.file.AccessDeniedException.class, viaLink::removeOrphansFromEarlierRuns);
        assertThrows(java.nio.file.AccessDeniedException.class, () -> viaLink.acquire("alex"));
    }

    @Test
    void directoryAndFilesAreOwnerOnlyAndPermissionsFailClosed() throws Exception {
        AccountExportFiles files = files();
        Path dir = own();
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
