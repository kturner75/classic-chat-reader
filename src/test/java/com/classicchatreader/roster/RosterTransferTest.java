package com.classicchatreader.roster;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import java.io.StringReader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RosterTransferTest {
    Connection c;
    @BeforeEach void setup() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        for (String migration : List.of("V1__baseline_schema.sql", "V4__account_auth.sql", "V11__character_call_voice.sql", "V17__character_chat_persistence.sql")) {
            RunScript.execute(c, new StringReader(Files.readString(Path.of("src/main/resources/db/migration", migration))));
        }
        sql("INSERT INTO books(id, source, source_id, title, author) VALUES ('book','gutenberg','17396','Book','Author'),('other','gutenberg','84','Other','Author')");
        sql("INSERT INTO chapters(id,book_id,chapter_index,title) VALUES ('ch','book',0,'First'),('foreign','other',0,'Other')");
        sql("INSERT INTO paragraphs(id,chapter_id,paragraph_index,content) VALUES ('p','ch',0,'Text'),('q','foreign',0,'Other')");
        sql("INSERT INTO characters(id,book_id,name,character_type,first_chapter_id,first_paragraph_index,status,created_at,portrait_filename,call_voice) VALUES ('a','book','Jo','PRIMARY','ch',0,'COMPLETED',CURRENT_TIMESTAMP,'jo.png','voice'),('b','book','Friedrich','SECONDARY','ch',0,'COMPLETED',CURRENT_TIMESTAMP,NULL,NULL),('x','other','Other','PRIMARY','foreign',0,'COMPLETED',CURRENT_TIMESTAMP,NULL,NULL)");
        sql("INSERT INTO users VALUES ('user','u@example.com','hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        sql("INSERT INTO character_chat_conversations VALUES ('chat','user','b',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        sql("INSERT INTO character_chat_messages(id,conversation_id,user_id,sequence_number,role,content,created_at) VALUES ('msg','chat','user',0,'USER','Hello',CURRENT_TIMESTAMP)");
        sql("INSERT INTO illustrations(id,chapter_id,created_at,status,image_filename) VALUES ('illo','ch',CURRENT_TIMESTAMP,'COMPLETED','illo.png')");
    }
    @AfterEach void close() throws Exception { c.close(); }
    void sql(String query) throws Exception { try (Statement s = c.createStatement()) { s.execute(query); } }
    String value(String query) throws Exception { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) { r.next(); return r.getString(1); } }
    RosterTransfer.Snapshot snapshot() throws Exception { return RosterTransfer.exportRoster(c,"gutenberg","17396"); }
    RosterTransfer.Row row(String id, String name, String type) { return new RosterTransfer.Row(id,name,type,0,0,null); }
    RosterTransfer.Plan plan(List<RosterTransfer.Row> rows, List<String> removals) throws Exception { return new RosterTransfer.Plan("gutenberg","17396",snapshot().revision(),rows,removals,true); }

    @Test void exportsCharactersWithoutPortraitsAndRemovalConsequences() throws Exception {
        var result = snapshot();
        assertEquals(2,result.characters().size());
        var removed = result.characters().stream().filter(r -> r.character().id().equals("b")).findFirst().orElseThrow();
        assertNull(removed.portraitFilename());
        assertEquals(1,removed.conversations()); assertEquals(1,removed.messages());
        assertEquals(result.revision(),snapshot().revision());
    }
    @Test void renameAddRemovePreservesRetainedAssetsAndCascadesChats() throws Exception {
        var result = RosterTransfer.apply(c,plan(List.of(row("a","Josephine","PRIMARY"),row(null,"Beth","SECONDARY")),List.of("b")));
        assertEquals(2,result.characters().size());
        assertEquals("Josephine",value("SELECT name FROM characters WHERE id='a'"));
        assertEquals("jo.png",value("SELECT portrait_filename FROM characters WHERE id='a'"));
        assertEquals("voice",value("SELECT call_voice FROM characters WHERE id='a'"));
        assertEquals("0",value("SELECT COUNT(*) FROM character_chat_messages"));
        assertEquals("illo.png",value("SELECT image_filename FROM illustrations WHERE id='illo'"));
        assertEquals("Other",value("SELECT name FROM characters WHERE id='x'"));
        assertEquals("COMPLETED",value("SELECT status FROM characters WHERE name='Beth'"));
        assertEquals("TRUE",value("SELECT character_prefetch_completed FROM books WHERE id='book'"));
        assertEquals("Josephine", result.characters().stream().filter(r -> r.character().id().equals("a")).findFirst().orElseThrow().character().name());
        assertTrue(result.characters().stream().noneMatch(r -> r.character().id().equals("b")));
        assertTrue(result.characters().stream().anyMatch(r -> "Beth".equals(r.character().name()) && r.character().id() != null));
    }
    @Test void demotionClearsOnlyCallVoiceAndPreservesPortrait() throws Exception {
        RosterTransfer.apply(c,plan(List.of(row("a","Jo","SECONDARY"),row("b","Friedrich","SECONDARY")),List.of()));
        assertNull(value("SELECT call_voice FROM characters WHERE id='a'"));
        assertEquals("jo.png",value("SELECT portrait_filename FROM characters WHERE id='a'"));
    }
    @Test void renameSwapPreservesIds() throws Exception {
        RosterTransfer.apply(c,plan(List.of(row("a","Friedrich","PRIMARY"),row("b","Jo","SECONDARY")),List.of()));
        assertEquals("Friedrich",value("SELECT name FROM characters WHERE id='a'"));
        assertEquals("Jo",value("SELECT name FROM characters WHERE id='b'"));
    }
    @Test void staleRevisionRejectsBeforeAnyWrite() throws Exception {
        var plan = plan(List.of(row("a","New","PRIMARY")),List.of("b"));
        sql("UPDATE characters SET name='Changed' WHERE id='b'");
        assertThrows(RosterTransfer.StalePlan.class,() -> RosterTransfer.apply(c,plan));
        assertEquals("Jo",value("SELECT name FROM characters WHERE id='a'"));
    }
    @Test void explicitDeletionListAndBookIdentityAreValidated() throws Exception {
        var incomplete = plan(List.of(row("a","New","PRIMARY")),List.of());
        var foreign = plan(List.of(row("x","New","PRIMARY")),List.of("a","b"));
        assertThrows(IllegalArgumentException.class,() -> RosterTransfer.apply(c,incomplete));
        assertThrows(IllegalArgumentException.class,() -> RosterTransfer.apply(c,foreign));
        assertEquals(2,snapshot().characters().size());
    }
    @Test void invalidPlacementAndEmptyRosterFailWithoutDeletingAnything() throws Exception {
        var bad = plan(List.of(new RosterTransfer.Row("a","Jo","PRIMARY",99,0,null)),List.of("b"));
        var empty = plan(List.of(),List.of("a","b"));
        assertThrows(IllegalArgumentException.class,() -> RosterTransfer.apply(c,bad));
        assertThrows(IllegalArgumentException.class,() -> RosterTransfer.apply(c,empty));
        assertEquals("1",value("SELECT COUNT(*) FROM character_chat_messages"));
    }
    @Test void midTransactionSqlFailureRollsBackDeletionAndRename() throws Exception {
        sql("ALTER TABLE characters ADD CONSTRAINT test_reject CHECK(name <> 'Rejected')");
        var rejected = plan(List.of(row("a","Rejected","PRIMARY")),List.of("b"));
        assertThrows(SQLException.class,() -> RosterTransfer.apply(c,rejected));
        assertEquals("Jo",value("SELECT name FROM characters WHERE id='a'"));
        assertEquals("1",value("SELECT COUNT(*) FROM character_chat_messages"));
    }
    @Test void activeGenerationBlocksReplacement() throws Exception {
        sql("UPDATE characters SET status='GENERATING' WHERE id='a'");
        var busy = plan(List.of(row("a","New","PRIMARY")),List.of("b"));
        assertThrows(RosterTransfer.StalePlan.class,() -> RosterTransfer.apply(c,busy));
    }
    @Test void retainedFailedRowIsResetAndPrefetchLatchesWhenCompleted() throws Exception {
        sql("UPDATE characters SET status='FAILED', error_message='boom', lease_owner='worker', lease_expires_at=CURRENT_TIMESTAMP, retry_count=3 WHERE id='a'");
        sql("UPDATE books SET character_prefetch_completed = FALSE WHERE id='book'");
        var result = RosterTransfer.apply(c,plan(List.of(row("a","Josephine","PRIMARY"),row("b","Friedrich","SECONDARY")),List.of()));
        assertEquals("COMPLETED",value("SELECT status FROM characters WHERE id='a'"));
        assertNull(value("SELECT error_message FROM characters WHERE id='a'"));
        assertNull(value("SELECT lease_owner FROM characters WHERE id='a'"));
        assertEquals("0",value("SELECT retry_count FROM characters WHERE id='a'"));
        assertEquals("Josephine",value("SELECT name FROM characters WHERE id='a'"));
        assertEquals("TRUE",value("SELECT character_prefetch_completed FROM books WHERE id='book'"));
        assertEquals("COMPLETED", result.characters().stream().filter(r -> r.character().id().equals("a")).findFirst().orElseThrow().status());
        assertEquals("Josephine", result.characters().stream().filter(r -> r.character().id().equals("a")).findFirst().orElseThrow().character().name());
    }
    @Test void revisionIgnoresLiveChatCounts() throws Exception {
        var exported = snapshot();
        sql("INSERT INTO character_chat_messages(id,conversation_id,user_id,sequence_number,role,content,created_at) VALUES ('msg2','chat','user',1,'USER','Later',CURRENT_TIMESTAMP)");
        assertEquals(exported.revision(), snapshot().revision());
        var applied = RosterTransfer.apply(c, new RosterTransfer.Plan("gutenberg","17396",exported.revision(),
                List.of(row("a","Josephine","PRIMARY"),row("b","Friedrich","SECONDARY")),List.of(),true));
        assertEquals("Josephine", applied.characters().stream().filter(r -> r.character().id().equals("a")).findFirst().orElseThrow().character().name());
        assertEquals(2, applied.characters().stream().filter(r -> r.character().id().equals("b")).findFirst().orElseThrow().messages());
    }
    @Test void localApiRoundTripUsesTheSameTransaction() throws Exception {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(c.getMetaData().getURL()); ds.setUser("sa"); ds.setPassword("");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.classicchatreader.controller.StudioRosterController(ds)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/studio/roster/gutenberg/17396"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.characters.length()").value(2));
        var plan = plan(List.of(row("a","Josephine","PRIMARY")),List.of("b"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/studio/roster/gutenberg/17396/replace")
                .contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals("Josephine",value("SELECT name FROM characters WHERE id='a'"));
    }

    @Test void competingPlansCannotBothCommit() throws Exception {
        var plan = plan(List.of(row("a","Josephine","PRIMARY")),List.of("b"));
        String url = c.getMetaData().getURL();
        var start = new java.util.concurrent.CountDownLatch(1);
        var work = (java.util.concurrent.Callable<Boolean>) () -> {
            start.await();
            try (Connection other = DriverManager.getConnection(url,"sa","")) {
                RosterTransfer.apply(other,plan); return true;
            } catch (SQLException | RosterTransfer.StalePlan e) { return false; }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = executor.submit(work); var two = executor.submit(work); start.countDown();
            assertNotEquals(one.get(),two.get());
        }
    }

}
