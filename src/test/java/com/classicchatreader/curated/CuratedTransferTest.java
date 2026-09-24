package com.classicchatreader.curated;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CuratedTransferTest {
    Connection c;

    @BeforeEach
    void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        c = DriverManager.getConnection(url, "sa", "");
    }

    @AfterEach
    void close() throws Exception { c.close(); }

    String value(String query) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) { return r.next() ? r.getString(1) : null; }
    }

    static CuratedTransfer.Membership dubliners(String status) {
        return new CuratedTransfer.Membership("Dubliners", "James Joyce", 23_500, List.of("Dublin (Ireland) -- Fiction"),
                List.of("Short Stories"), List.of("Araby", "The Dead"), status);
    }

    @Test
    void exportsASeededRowWithAStableRevision() throws Exception {
        var snapshot = CuratedTransfer.exportMembership(c, "gutenberg", "1513");
        assertEquals("Romeo and Juliet", snapshot.membership().title());
        assertEquals(List.of("Plays", "Tragedy"), snapshot.membership().bookshelves());
        assertEquals("active", snapshot.membership().status());
        assertEquals(snapshot.revision(), CuratedTransfer.exportMembership(c, "gutenberg", "1513").revision());
    }

    @Test
    void listsANewTitleFromAnAbsentRow() throws Exception {
        var absent = CuratedTransfer.exportMembership(c, "gutenberg", "2814");
        assertNull(absent.membership());

        var applied = CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2814", absent.revision(), dubliners("active"), true));

        assertEquals(dubliners("active"), applied.membership());
        assertEquals("gutenberg:2814", value("SELECT id FROM curated_books WHERE source_id = '2814'"));
        assertEquals("[\"Araby\",\"The Dead\"]", value("SELECT aliases_json FROM curated_books WHERE source_id = '2814'"));
    }

    @Test
    void unlistsWithoutTouchingTheBookOrOtherRows() throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO books (id, source, source_id, title, author, character_enabled) VALUES ('romeo', 'gutenberg', '1513', 'Romeo and Juliet', 'William Shakespeare', TRUE)");
        }
        String activeBefore = value("SELECT COUNT(*) FROM curated_books WHERE status = 'active'");
        var before = CuratedTransfer.exportMembership(c, "gutenberg", "1513");
        var m = before.membership();
        var inactive = new CuratedTransfer.Membership(m.title(), m.author(), m.popularity(), m.subjects(), m.bookshelves(), m.aliases(), "inactive");

        CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "1513", before.revision(), inactive, true));

        assertEquals("inactive", value("SELECT status FROM curated_books WHERE source_id = '1513'"));
        assertEquals("TRUE", value("SELECT character_enabled FROM books WHERE id = 'romeo'"));
        assertEquals(Integer.parseInt(activeBefore) - 1, Integer.parseInt(value("SELECT COUNT(*) FROM curated_books WHERE status = 'active'")));
    }

    @Test
    void storesTheSameNormalizedValuesTheLocalApiWould() throws Exception {
        var absent = CuratedTransfer.exportMembership(c, "gutenberg", "2814");
        var messy = new CuratedTransfer.Membership("  Dubliners ", " James Joyce", 0, List.of(" Dublin "),
                List.of("Short Stories", "  "), List.of(" Araby "), "active");

        var applied = CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2814", absent.revision(), messy, true));

        assertEquals(new CuratedTransfer.Membership("Dubliners", "James Joyce", 0, List.of("Dublin"),
                List.of("Short Stories"), List.of("Araby"), "active"), applied.membership());
    }

    @Test
    void refusesAStalePlanWithoutWriting() throws Exception {
        var absent = CuratedTransfer.exportMembership(c, "gutenberg", "2814");
        CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2814", absent.revision(), dubliners("active"), true));

        var stale = new CuratedTransfer.Plan("gutenberg", "2814", absent.revision(), dubliners("inactive"), true);
        assertThrows(CuratedTransfer.StalePlan.class, () -> CuratedTransfer.apply(c, stale));
        assertEquals("active", value("SELECT status FROM curated_books WHERE source_id = '2814'"));
    }

    @Test
    void rejectsUnconfirmedOrInvalidPlansWithoutWriting() throws Exception {
        String revision = CuratedTransfer.exportMembership(c, "gutenberg", "2814").revision();
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2814", revision, dubliners("active"), false)));
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2814", revision, dubliners("hidden"), true)));
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2814", revision,
                new CuratedTransfer.Membership("x".repeat(513), "James Joyce", 0, List.of(), List.of(), List.of(), "active"), true)));
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.exportMembership(c, "standardebooks", "2814"));
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.exportMembership(c, "gutenberg", "abc"));
        // Beyond int range: the catalog could never read such a row back.
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.exportMembership(c, "gutenberg", "2147483648"));
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2147483648", revision, dubliners("active"), true)));
        assertThrows(IllegalArgumentException.class, () -> CuratedTransfer.apply(c, new CuratedTransfer.Plan("gutenberg", "2814", revision,
                new CuratedTransfer.Membership("Dubliners", "James Joyce", -1, List.of(), List.of(), List.of(), "active"), true)));
        assertNull(value("SELECT id FROM curated_books WHERE source_id = '2814'"));
    }
}
