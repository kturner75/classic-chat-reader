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
        jdbc.update("INSERT INTO class_feature_settings (term_id, quiz_enabled, recap_enabled, tts_enabled, illustration_enabled, character_enabled, chat_enabled, speed_reading_enabled, reading_buddy_enabled, updated_at, updated_by_user_id) "
                + "VALUES ('fx-term', TRUE, FALSE, TRUE, TRUE, TRUE, TRUE, TRUE, FALSE, CURRENT_TIMESTAMP, 'fx-teacher')");
        jdbc.update("INSERT INTO assignment_chapters (id, assignment_id, chapter_id, chapter_index, sort_order) VALUES ('ac-1', 'fx-assignment', 'fx-ch', 0, 0)");
        jdbc.update("INSERT INTO assignment_quizzes (id, assignment_id, payload_json, created_by_user_id, created_at, updated_at) "
                + "VALUES ('aq-1', 'fx-assignment', '{\"questions\":[{\"prompt\":\"Who is Darcy?\"}]}', 'fx-teacher', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO quiz_question_overrides (id, term_id, book_id, chapter_id, operation, overlay_key, sort_order, question_json, status, created_by_user_id, created_at, updated_at) "
                + "VALUES ('qqo-1', 'fx-term', 'fx-book', 'fx-ch', 'ADD', 'teacher-q1', 0, '{\"prompt\":\"Why Longbourn?\"}', 'ACTIVE', 'fx-teacher', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO invite_links (id, term_id, code_hash, code_hint, label, max_uses, use_count, created_by_user_id, created_at, updated_at) "
                + "VALUES ('il-1', 'fx-term', 'secret-invite-hash', 'AB12', 'Period 3', 40, 2, 'fx-teacher', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        String raw = new String(service.export("fx-teacher"), StandardCharsets.UTF_8);
        JsonNode doc = new ObjectMapper().readTree(raw);
        JsonNode teacher = doc.at("/classroom/teacherContent");
        assertEquals("Fall", teacher.at("/classTerms/0/name").asText());
        assertFalse(teacher.at("/featureSettings/0/recap_enabled").asBoolean());
        assertEquals("Read Chapter I", teacher.at("/assignments/0/title").asText());
        assertEquals("fx-ch", teacher.at("/assignmentChapters/0/chapter_id").asText());
        assertEquals("Who is Darcy?", teacher.at("/assignmentQuizzes/0/payload_json/questions/0/prompt").asText(), "stored JSON is exported as JSON");
        assertEquals("Why Longbourn?", teacher.at("/quizQuestionOverrides/0/question_json/prompt").asText());
        assertEquals("Period 3", teacher.at("/inviteLinks/0/label").asText());
        assertFalse(raw.contains("secret-invite-hash"), "invite code hashes are secrets");
        assertFalse(raw.contains("AB12"), "invite code hints are not exported");
        assertEquals("TEACHER", doc.at("/classroom/teachingRoles/0/role").asText());
        assertEquals("English 101", doc.at("/classroom/teachingRoles/0/class_name").asText());
        assertEquals(0, export("fx-alex").at("/classroom/teacherContent/assignments").size(), "students have no authored classwork");
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
    void characterChatsKeepTheirResumeContext() throws Exception {
        jdbc.update("UPDATE character_chat_conversations SET context_chapter_id = 'fx-ch', context_chapter_index = 0, "
                + "context_chapter_title = 'Chapter I', context_paragraph_index = 7 WHERE user_id = 'fx-alex'");
        JsonNode chat = export("fx-alex").at("/characterChats/0");
        assertEquals("fx-ch", chat.at("/context_chapter_id").asText());
        assertEquals(0, chat.at("/context_chapter_index").asInt());
        assertEquals("Chapter I", chat.at("/context_chapter_title").asText());
        assertEquals(7, chat.at("/context_paragraph_index").asInt());
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
