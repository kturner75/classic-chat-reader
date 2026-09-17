package com.classicchatreader.style;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StyleTransferTest {
    Connection c;
    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        migrate(url);
        c = DriverManager.getConnection(url, "sa", "");
        sql("INSERT INTO books(id, source, source_id, title, author, illustration_style, illustration_prompt_prefix, illustration_setting, illustration_cover_subject, illustration_cover_focus) "
                + "VALUES ('book','gutenberg','1342','Book','Author','watercolor','soft watercolor,','Regency England','character','Elizabeth'),"
                + "('other','gutenberg','84','Other','Author','ink','ink,',NULL,NULL,NULL)");
    }
    /** The full Flyway chain, including Java migrations such as V30 (cover focus TEXT). */
    static void migrate(String url) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
    }
    @AfterEach void close() throws Exception { c.close(); }
    void sql(String query) throws Exception { try (Statement s = c.createStatement()) { s.execute(query); } }
    String value(String query) throws Exception { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) { r.next(); return r.getString(1); } }
    StyleTransfer.Snapshot snapshot() throws Exception { return StyleTransfer.exportStyle(c, "gutenberg", "1342"); }
    StyleTransfer.Style oil() { return new StyleTransfer.Style(" painterly oil ", "painterly oil,", "", "Studio confirmed style draft", null, "  "); }
    StyleTransfer.Plan plan(StyleTransfer.Style style) throws Exception { return new StyleTransfer.Plan("gutenberg", "1342", snapshot().revision(), style, true); }

    @Test void exportsStyleWithStableRevision() throws Exception {
        var result = snapshot();
        assertEquals("watercolor", result.style().style());
        assertEquals("Elizabeth", result.style().coverFocus());
        assertEquals(result.revision(), snapshot().revision());
    }
    @Test void replacesAllFieldsTrimmedAndClearsBlanksWithoutTouchingOtherBooks() throws Exception {
        var result = StyleTransfer.apply(c, plan(oil()));
        assertEquals(new StyleTransfer.Style("painterly oil", "painterly oil,", null, "Studio confirmed style draft", null, null), result.style());
        assertNull(value("SELECT illustration_setting FROM books WHERE id='book'"));
        assertNull(value("SELECT illustration_cover_subject FROM books WHERE id='book'"));
        assertEquals("ink", value("SELECT illustration_style FROM books WHERE id='other'"));
    }
    @Test void stalePlanAndUnconfirmedPlanDoNotWrite() throws Exception {
        var stale = plan(oil());
        sql("UPDATE books SET illustration_setting = 'Hertfordshire' WHERE id='book'");
        assertThrows(StyleTransfer.StalePlan.class, () -> StyleTransfer.apply(c, stale));
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.apply(c, new StyleTransfer.Plan("gutenberg", "1342", snapshot().revision(), oil(), false)));
        assertEquals("watercolor", value("SELECT illustration_style FROM books WHERE id='book'"));
    }
    @Test void rejectsBlankRequiredFieldsAndEveryOverlongFieldWithoutWriting() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.apply(c, plan(new StyleTransfer.Style("oil", " ", null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.apply(c, plan(new StyleTransfer.Style(null, "oil,", null, null, null, null))));
        for (StyleTransfer.Style tooLong : List.of(
                new StyleTransfer.Style("x".repeat(256), "oil,", null, null, null, null),
                new StyleTransfer.Style("oil", "x".repeat(1001), null, null, null, null),
                new StyleTransfer.Style("oil", "oil,", "x".repeat(1001), null, null, null),
                new StyleTransfer.Style("oil", "oil,", null, "x".repeat(2001), null, null),
                new StyleTransfer.Style("oil", "oil,", null, null, "x".repeat(33), null),
                new StyleTransfer.Style("oil", "oil,", null, null, null, "x".repeat(4001)))) {
            assertThrows(IllegalArgumentException.class, () -> StyleTransfer.apply(c, plan(tooLong)), tooLong.toString().substring(0, 40));
        }
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.exportStyle(c, "gutenberg", "999"));
        assertEquals("watercolor", value("SELECT illustration_style FROM books WHERE id='book'"));
        assertTrue(c.getAutoCommit());
    }
    @Test void acceptsEveryFieldAtItsLimitIncludingLongCoverFocusAfterV30() throws Exception {
        var atLimit = new StyleTransfer.Style("s".repeat(255), "p".repeat(1000), "e".repeat(1000), "r".repeat(2000), "c".repeat(32), "f".repeat(4000));
        assertEquals(atLimit, StyleTransfer.apply(c, plan(atLimit)).style());
        assertEquals(4000, value("SELECT illustration_cover_focus FROM books WHERE id='book'").length());
    }
}
