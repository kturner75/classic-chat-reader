package com.classicchatreader.style;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import java.io.StringReader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StyleTransferTest {
    Connection c;
    @BeforeEach void setup() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        for (String migration : List.of("V1__baseline_schema.sql", "V29__book_cover_subject.sql"))
            RunScript.execute(c, new StringReader(Files.readString(Path.of("src/main/resources/db/migration", migration))));
        sql("INSERT INTO books(id, source, source_id, title, author, illustration_style, illustration_prompt_prefix, illustration_setting, illustration_cover_subject, illustration_cover_focus) "
                + "VALUES ('book','gutenberg','1342','Book','Author','watercolor','soft watercolor,','Regency England','character','Elizabeth'),"
                + "('other','gutenberg','84','Other','Author','ink','ink,',NULL,NULL,NULL)");
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
    @Test void rejectsBlankRequiredFieldsOverlongValuesAndMissingBooks() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.apply(c, plan(new StyleTransfer.Style("oil", " ", null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.apply(c, plan(new StyleTransfer.Style("oil", "oil,", null, null, "x".repeat(33), null))));
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.apply(c, plan(new StyleTransfer.Style("oil", "oil,", null, null, null, "x".repeat(501)))));
        assertThrows(IllegalArgumentException.class, () -> StyleTransfer.exportStyle(c, "gutenberg", "999"));
        assertEquals("watercolor", value("SELECT illustration_style FROM books WHERE id='book'"));
        assertTrue(c.getAutoCommit());
    }
}
