package com.classicchatreader.service;

import com.classicchatreader.support.AccountDataFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(AccountDataExportService.class)
class AccountDataExportServiceTest {

    @Autowired private AccountDataExportService service;
    @Autowired private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        AccountDataFixture.seedShared(jdbc, "fx-teacher");
        AccountDataFixture.seedStudent(jdbc, "fx-alex", "alex");
        AccountDataFixture.seedStudent(jdbc, "fx-sam", "sam");
    }

    private JsonNode export(String userId) throws Exception {
        return new ObjectMapper().readTree(service.export(userId));
    }

    @Test
    void exportsEverySectionOfTheAccountsOwnDataAndNothingElse() throws Exception {
        byte[] bytes = service.export("fx-alex");
        String raw = new String(bytes, StandardCharsets.UTF_8);
        JsonNode doc = new ObjectMapper().readTree(bytes);

        assertTrue(doc.at("/exportedAt").asText().endsWith("Z"), "export time carries its zone");
        assertEquals("fx-alex@example.test", doc.at("/account/email").asText());
        assertEquals("alex", doc.at("/readerState/state_json/tag").asText());
        assertEquals("note alex", doc.at("/annotations/0/note_text").asText());
        assertEquals("Pride and Prejudice", doc.at("/annotations/0/book_title").asText());
        assertEquals(80, doc.at("/quizAttempts/0/score_percent").asInt());
        assertEquals("first-alex", doc.at("/quizTrophies/0/code").asText());
        assertEquals("Mr. Darcy", doc.at("/characterChats/0/character_name").asText());
        assertEquals("character chat alex", doc.at("/characterChats/0/messages/0/content").asText());
        assertEquals("buddy chat alex", doc.at("/readingBuddy/messages/0/content").asText());
        assertEquals("memory alex", doc.at("/readingBuddy/memories/0/summary_text").asText());
        assertEquals("rare", doc.at("/readingBuddy/preferences/0/frequency").asText());
        assertEquals("English 101", doc.at("/classroom/enrollments/0/class_name").asText());
        assertEquals("fx-assignment", doc.at("/classroom/assignmentProgress/0/assignment_id").asText());
        assertEquals(60000, doc.at("/classroom/usageEvents/0/duration_ms").asInt());
        assertEquals(0, doc.at("/classroom/teachingRoles").size());

        for (String other : List.of("fx-sam@example.test", "note sam", "character chat sam", "buddy chat sam", "memory sam", "first-sam", "\"tag\" : \"sam\"")) {
            assertFalse(raw.contains(other), "another student's data leaked: " + other);
        }
        assertFalse(raw.contains("secret-hash"), "password hashes must never be exported");
        assertFalse(raw.contains("token-alex"), "session tokens must never be exported");
        assertFalse(raw.contains("reader-alex"), "internal anonymous reader ids are not exported");
    }

    @Test
    void teacherExportIncludesTeachingRolesAndOwnedClasses() throws Exception {
        jdbc.update("INSERT INTO class_role_memberships (id, term_id, user_id, role, status, created_at, updated_at) VALUES ('crm-t', 'fx-term', 'fx-teacher', 'TEACHER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        JsonNode doc = export("fx-teacher");
        assertEquals("TEACHER", doc.at("/classroom/teachingRoles/0/role").asText());
        assertEquals("English 101", doc.at("/classroom/teachingRoles/0/class_name").asText());
        assertEquals("fx-class", doc.at("/classroom/ownedClasses/0/class_id").asText());
        assertEquals(0, doc.at("/classroom/enrollments").size());
        assertFalse(doc.toString().contains("buddy chat alex"), "a teacher's export never contains students' data");
    }

    @Test
    void softDeletedRowsTheAccountStillOwnsAreIncluded() throws Exception {
        jdbc.update("UPDATE enrollments SET deleted_at = CURRENT_TIMESTAMP WHERE user_id = 'fx-alex'");
        jdbc.update("UPDATE classroom_usage_events SET deleted_at = CURRENT_TIMESTAMP WHERE user_id = 'fx-alex'");
        JsonNode doc = export("fx-alex");
        assertEquals(1, doc.at("/classroom/enrollments").size());
        assertFalse(doc.at("/classroom/enrollments/0/deleted_at").isNull());
        assertEquals(1, doc.at("/classroom/usageEvents").size());
    }

    @Test
    void longHistoriesStreamCompletelyAndLeaveTheCallersStreamOpen() throws Exception {
        for (int i = 0; i < 5000; i++) {
            jdbc.update("INSERT INTO classroom_usage_events (id, user_id, term_id, event_type, duration_ms, occurred_at, created_at) VALUES (?, 'fx-alex', 'fx-term', 'READING_HEARTBEAT', 60000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "hb-" + i);
        }
        boolean[] closed = {false};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        service.writeExport("fx-alex", out);
        assertFalse(closed[0], "the servlet owns the response stream");
        assertEquals(5001, new ObjectMapper().readTree(out.toByteArray()).at("/classroom/usageEvents").size());
    }

    @Test
    void conversationsWithoutMessagesStillExport() throws Exception {
        jdbc.update("DELETE FROM character_chat_messages WHERE user_id = 'fx-alex'");
        JsonNode doc = export("fx-alex");
        assertEquals("Mr. Darcy", doc.at("/characterChats/0/character_name").asText());
        assertEquals(0, doc.at("/characterChats/0/messages").size());
    }

    @Test
    void unknownAccountIsRejected() {
        assertFalse(service.accountExists("nobody"));
        assertThrows(IllegalArgumentException.class, () -> service.export("nobody"));
    }
}
