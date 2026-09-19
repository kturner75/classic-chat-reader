package com.classicchatreader.service;

import com.classicchatreader.support.AccountDataFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every column of every table "Download my data" draws from must be exported or deliberately
 * omitted here, with a reason. A new migration column fails this test until someone decides.
 */
@DataJpaTest
@Import(AccountDataExportService.class)
class AccountDataExportColumnPolicyTest {

    private static final String SELF = "the account itself (implicit)";
    private static final String INTERNAL = "internal row id or pointer, meaningless outside this database";

    /** section path in the export -> table, omitted columns (with reasons), renamed columns. */
    private record Section(String account, String path, String table, Map<String, String> omitted, Map<String, String> renamed) {}

    private static final List<Section> SECTIONS = List.of(
            new Section("student", "/account", "users", Map.of(), Map.of()),
            new Section("student", "/readerState", "user_reader_states", Map.of("user_id", SELF), Map.of()),
            new Section("student", "/annotations/0", "paragraph_annotations",
                    Map.of("id", INTERNAL, "user_id", SELF, "reader_id", "anonymous browser id from before sign-in"), Map.of()),
            new Section("student", "/quizAttempts/0", "quiz_attempts",
                    Map.of("id", INTERNAL, "user_id", SELF, "reader_id", "anonymous browser id from before sign-in"), Map.of()),
            new Section("student", "/quizTrophies/0", "quiz_trophies",
                    Map.of("id", INTERNAL, "user_id", SELF, "reader_id", "anonymous browser id from before sign-in"), Map.of()),
            new Section("student", "/characterChats/0", "character_chat_conversations",
                    Map.of("id", "messages are nested under their conversation", "user_id", SELF), Map.of()),
            new Section("student", "/characterChats/0/messages/0", "character_chat_messages",
                    Map.of("id", INTERNAL, "conversation_id", "nested under its conversation", "user_id", SELF,
                            "client_message_id", "client retry/idempotency token"), Map.of()),
            new Section("student", "/readingBuddy/messages/0", "reading_buddy_messages",
                    Map.of("id", INTERNAL, "owner_key", SELF, "content_hash", "derived from content",
                            "chronology_sequence", "ordering only; export order preserves it"), Map.of()),
            new Section("student", "/readingBuddy/memories/0", "reading_buddy_memories",
                    Map.of("id", INTERNAL, "owner_key", SELF, "last_message_id", INTERNAL), Map.of()),
            new Section("student", "/readingBuddy/preferences/0", "reading_buddy_preferences",
                    Map.of("id", INTERNAL, "owner_key", SELF), Map.of()),
            new Section("student", "/classroom/enrollments/0", "enrollments",
                    Map.of("id", INTERNAL, "user_id", SELF, "invite_link_id", INTERNAL), Map.of()),
            new Section("student", "/classroom/assignmentProgress/0", "assignment_progress",
                    Map.of("id", INTERNAL, "user_id", SELF), Map.of()),
            new Section("student", "/classroom/usageEvents/0", "classroom_usage_events",
                    Map.of("id", INTERNAL, "user_id", SELF, "session_id", "ephemeral client session token",
                            "idempotency_key", "client retry/idempotency token"), Map.of()),
            new Section("teacher", "/classroom/teachingRoles/0", "class_role_memberships",
                    Map.of("id", INTERNAL, "user_id", SELF), Map.of()),
            new Section("teacher", "/classroom/ownedClasses/0", "class_sections",
                    Map.of("owner_user_id", SELF), Map.of("id", "class_id")),
            new Section("teacher", "/classroom/schoolMemberships/0", "school_memberships",
                    Map.of("id", INTERNAL, "user_id", SELF), Map.of()),
            new Section("teacher", "/classroom/teacherContent/classTerms/0", "terms",
                    Map.of(), Map.of("id", "term_id", "class_section_id", "class_id")),
            new Section("teacher", "/classroom/teacherContent/featureSettings/0", "class_feature_settings",
                    Map.of("updated_by_user_id", "may be another teacher's account id"), Map.of()),
            new Section("teacher", "/classroom/teacherContent/assignments/0", "assignments",
                    Map.of("created_by_user_id", "may be another teacher's account id"), Map.of("id", "assignment_id")),
            new Section("teacher", "/classroom/teacherContent/assignmentChapters/0", "assignment_chapters",
                    Map.of("id", INTERNAL), Map.of()),
            new Section("teacher", "/classroom/teacherContent/assignmentQuizzes/0", "assignment_quizzes",
                    Map.of("id", INTERNAL, "created_by_user_id", "may be another teacher's account id"), Map.of()),
            new Section("teacher", "/classroom/teacherContent/quizQuestionOverrides/0", "quiz_question_overrides",
                    Map.of("id", INTERNAL, "created_by_user_id", SELF), Map.of()),
            new Section("teacher", "/classroom/teacherContent/inviteLinks/0", "invite_links",
                    Map.of("id", INTERNAL, "code_hash", "secret", "code_hint", "secret", "created_by_user_id", SELF,
                            "replaced_by_link_id", INTERNAL), Map.of()));

    @Autowired private AccountDataExportService service;
    @Autowired private DataSource dataSource;

    @Test
    void everyColumnIsExportedOrDeliberatelyOmitted() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AccountDataFixture.seedShared(jdbc, "fx-teacher");
        AccountDataFixture.seedStudent(jdbc, "fx-alex", "alex");
        AccountDataFixture.seedTeacherContent(jdbc, "fx-teacher");
        Map<String, JsonNode> exports = Map.of(
                "student", new ObjectMapper().readTree(service.export("fx-alex")),
                "teacher", new ObjectMapper().readTree(service.export("fx-teacher")));

        List<String> problems = new ArrayList<>();
        for (Section section : SECTIONS) {
            Set<String> columns = new TreeSet<>(jdbc.queryForList(
                    "SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?", String.class, section.table()));
            assertTrue(!columns.isEmpty(), "unknown table " + section.table());
            for (String omitted : section.omitted().keySet()) {
                if (!columns.contains(omitted)) problems.add(section.table() + "." + omitted + " is omitted but no longer exists");
            }
            JsonNode row = exports.get(section.account()).at(section.path());
            if (row.isMissingNode() || !row.isObject()) {
                problems.add(section.path() + " has no seeded row to check " + section.table());
                continue;
            }
            Set<String> keys = new HashSet<>();
            row.fieldNames().forEachRemaining(keys::add);
            for (String column : columns) {
                if (section.omitted().containsKey(column)) continue;
                String exported = section.renamed().getOrDefault(column, column);
                if (!keys.contains(exported)) {
                    problems.add(section.table() + "." + column + " is neither exported at " + section.path() + " nor omitted with a reason");
                }
            }
        }
        if (!problems.isEmpty()) fail(String.join("\n", problems));
    }
}
