package com.classicchatreader.cli;

import com.classicchatreader.style.StyleTransfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StyleTransferRunnerTest {
    @TempDir Path dir;
    @Test void exportThenExplicitReplacementProducesVerifiedReceipt() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO books(id,source,source_id,title,author) VALUES ('b','gutenberg','1342','Title','Author')");
            }
        }
        Path output = dir.resolve("style result.json"), input = dir.resolve("plan.json");
        var out = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, StyleTransferRunner.run(new String[]{"export", "--source", "gutenberg", "--source-id", "1342", "--output", output.toString(), "--db-url", url}, out, out));
        ObjectMapper json = new ObjectMapper();
        var snapshot = json.readValue(output.toFile(), StyleTransfer.Snapshot.class);
        assertNull(snapshot.style().style());
        json.writeValue(input.toFile(), new StyleTransfer.Plan("gutenberg", "1342", snapshot.revision(),
                new StyleTransfer.Style("watercolor", "soft watercolor,", "Regency England", null, "character", "Elizabeth"), true));
        String[] args = {"replace", "--source", "gutenberg", "--source-id", "1342", "--output", output.toString(), "--input", input.toString(), "--db-url", url, "--apply"};
        assertEquals(0, StyleTransferRunner.run(args, out, out));
        assertEquals("Elizabeth", json.readValue(output.toFile(), StyleTransfer.Snapshot.class).style().coverFocus());
        assertEquals(2, StyleTransferRunner.run(args, out, out), "same plan cannot be reapplied after revision changes");
    }
    @Test void replacementNeedsExplicitApplyAndBook() {
        var out = new PrintStream(new ByteArrayOutputStream());
        assertEquals(1, StyleTransferRunner.run(new String[]{"replace", "--source", "gutenberg", "--source-id", "1342", "--output", dir.resolve("out.json").toString()}, out, out));
        assertEquals(1, StyleTransferRunner.run(new String[]{"export", "--output", dir.resolve("out.json").toString()}, out, out));
    }
}
