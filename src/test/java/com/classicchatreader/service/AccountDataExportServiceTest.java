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
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(AccountDataExportService.class)
class AccountDataExportServiceTest {

    @Autowired private AccountDataExportService service;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void seed() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AccountDataFixture.seedShared(jdbc, "fx-teacher");
        AccountDataFixture.seedStudent(jdbc, "fx-alex", "alex");
        AccountDataFixture.seedStudent(jdbc, "fx-sam", "sam");
    }

    @Test
    void exportsEverySectionOfTheAccountsOwnDataAndNothingElse() throws Exception {
        byte[] bytes = service.export("fx-alex");
        String raw = new String(bytes, StandardCharsets.UTF_8);
        JsonNode doc = new ObjectMapper().readTree(bytes);

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
        assertEquals(60000, doc.at("/classroom/usageEvents/0/duration_ms").asInt());

        assertFalse(raw.contains("sam"), "another student's data must never appear");
        assertFalse(raw.contains("secret-hash"), "password hashes must never be exported");
        assertFalse(raw.contains("token-alex"), "session tokens must never be exported");
        assertFalse(raw.contains("reader-alex"), "internal anonymous reader ids are not exported");
    }

    @Test
    void unknownAccountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.export("nobody"));
    }
}
