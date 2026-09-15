package com.classicchatreader.cli;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.roster.RosterTransfer;
import static org.junit.jupiter.api.Assertions.*;

class RosterTransferRunnerTest {
    @TempDir Path dir;
    @Test void exportThenExplicitReplacementProducesVerifiedReceipt() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url,"sa","")) {
            for (String migration : List.of("V1__baseline_schema.sql","V4__account_auth.sql","V11__character_call_voice.sql","V17__character_chat_persistence.sql"))
                RunScript.execute(c,new StringReader(Files.readString(Path.of("src/main/resources/db/migration",migration))));
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO books(id,source,source_id,title,author) VALUES ('b','gutenberg','17396','Title','Author')");
                s.execute("INSERT INTO chapters VALUES ('c','b',0,'First')");
                s.execute("INSERT INTO paragraphs VALUES ('p','c',0,'Text')");
            }
        }
        Path output = dir.resolve("roster result.json"), input = dir.resolve("plan.json");
        var out = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0,RosterTransferRunner.run(new String[]{"export","--source","gutenberg","--source-id","17396","--output",output.toString(),"--db-url",url},out,out));
        ObjectMapper json = new ObjectMapper();
        var snapshot = json.readValue(output.toFile(),RosterTransfer.Snapshot.class);
        assertEquals(0,snapshot.characters().size());
        json.writeValue(input.toFile(),new RosterTransfer.Plan("gutenberg","17396",snapshot.revision(),List.of(new RosterTransfer.Row(null,"Jo","PRIMARY",0,0,null)),List.of(),true));
        String[] args = {"replace","--source","gutenberg","--source-id","17396","--output",output.toString(),"--input",input.toString(),"--db-url",url,"--apply"};
        assertEquals(0,RosterTransferRunner.run(args,out,out));
        assertEquals("Jo",json.readValue(output.toFile(),RosterTransfer.Snapshot.class).characters().getFirst().character().name());
        assertEquals(2,RosterTransferRunner.run(args,out,out),"same plan cannot be reapplied after revision changes");
    }
    @Test void replacementNeedsExplicitApplyAndBook() {
        var out = new PrintStream(new ByteArrayOutputStream());
        assertEquals(1,RosterTransferRunner.run(new String[]{"replace","--source","gutenberg","--source-id","17396","--output",dir.resolve("out.json").toString()},out,out));
        assertEquals(1,RosterTransferRunner.run(new String[]{"export","--output",dir.resolve("out.json").toString()},out,out));
    }
    @Test void unexpectedFailurePrintsTheUnderlyingMessage() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = RosterTransferRunner.run(new String[]{"export","--source","gutenberg","--source-id","17396",
                "--output",dir.resolve("out.json").toString(),"--db-url","jdbc:doesnotexist:foo"},
                new PrintStream(out), new PrintStream(err));
        assertEquals(1, code);
        String message = err.toString();
        assertTrue(message.contains("Roster operation failed:"), message);
        assertTrue(message.contains("No suitable driver") || message.toLowerCase().contains("driver"), message);
        assertTrue(message.contains("Re-export the destination before retrying"), message);
    }
}
