package com.classicchatreader.cli;

import com.classicchatreader.curated.CuratedTransfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CuratedTransferRunnerTest {
    @TempDir Path dir;

    @Test
    void exportThenExplicitReplacementProducesVerifiedReceipt() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        Path output = dir.resolve("curated result.json"), input = dir.resolve("plan.json");
        var out = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, CuratedTransferRunner.run(new String[]{"export", "--source", "gutenberg", "--source-id", "2814", "--output", output.toString(), "--db-url", url}, out, out));
        ObjectMapper json = new ObjectMapper();
        var snapshot = json.readValue(output.toFile(), CuratedTransfer.Snapshot.class);
        assertNull(snapshot.membership());
        json.writeValue(input.toFile(), new CuratedTransfer.Plan("gutenberg", "2814", snapshot.revision(),
                new CuratedTransfer.Membership("Dubliners", "James Joyce", 23_500, List.of(), List.of("Short Stories"), List.of("Araby"), "active"), true));
        String[] args = {"replace", "--source", "gutenberg", "--source-id", "2814", "--output", output.toString(), "--input", input.toString(), "--db-url", url, "--apply"};
        assertEquals(0, CuratedTransferRunner.run(args, out, out));
        assertEquals("active", json.readValue(output.toFile(), CuratedTransfer.Snapshot.class).membership().status());
        assertEquals(2, CuratedTransferRunner.run(args, out, out), "same plan cannot be reapplied after revision changes");
    }

    @Test
    void replacementNeedsExplicitApplyAndMatchingBook() throws Exception {
        var out = new PrintStream(new ByteArrayOutputStream());
        Path plan = dir.resolve("plan.json");
        new ObjectMapper().writeValue(plan.toFile(), new CuratedTransfer.Plan("gutenberg", "84", "r", null, true));
        assertEquals(1, CuratedTransferRunner.run(new String[]{"replace", "--source", "gutenberg", "--source-id", "2814", "--output", dir.resolve("out.json").toString()}, out, out));
        assertEquals(1, CuratedTransferRunner.run(new String[]{"replace", "--source", "gutenberg", "--source-id", "2814", "--input", plan.toString(),
                "--output", dir.resolve("out.json").toString(), "--db-url", "jdbc:h2:mem:unused", "--apply"}, out, out));
    }
}
