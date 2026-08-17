package com.classicchatreader.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterNameIdentityMigrationTest {

    @Test
    void mergesNormalizedDuplicatesAndRepointsConversations() throws Exception {
        String url = "jdbc:h2:mem:character-name-identity-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway toV30 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("30")
                .cleanDisabled(false)
                .load();
        toV30.clean();
        toV30.migrate();
        assertEquals("30", toV30.info().current().getVersion().getVersion());

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO books (id, title, author, source)
                    VALUES ('book-1', 'Northanger Abbey', 'Jane Austen', 'gutenberg')
                    """);
            statement.executeUpdate("""
                    INSERT INTO chapters (id, book_id, chapter_index, title)
                    VALUES ('chapter-1', 'book-1', 0, 'Chapter 1')
                    """);
            statement.executeUpdate("""
                    INSERT INTO characters
                        (id, book_id, character_type, created_at, first_chapter_id,
                         first_paragraph_index, name, retry_count, status, portrait_filename)
                    VALUES
                        ('sally-pending', 'book-1', 'SECONDARY', TIMESTAMP '2026-08-01 10:00:00',
                         'chapter-1', 3, 'Sally', 0, 'PENDING', NULL),
                        ('sally-winner', 'book-1', 'PRIMARY', TIMESTAMP '2026-08-01 11:00:00',
                         'chapter-1', 1, 'Sally.', 0, 'COMPLETED', 'portrait-sally.png'),
                        ('sally-case', 'book-1', 'SECONDARY', TIMESTAMP '2026-08-01 12:00:00',
                         'chapter-1', 8, 'SALLY', 0, 'FAILED', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO users (id, email, created_at, updated_at)
                    VALUES ('user-1', 'reader@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO character_chat_conversations
                        (id, user_id, character_id, created_at, updated_at)
                    VALUES
                        ('chat-pending', 'user-1', 'sally-pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('chat-case', 'user-1', 'sally-case', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }

        Flyway toV31 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        toV31.migrate();
        assertEquals("31", toV31.info().current().getVersion().getVersion());

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM characters WHERE book_id = 'book-1'"));
            assertEquals("sally-winner", scalar(statement, "SELECT id FROM characters WHERE book_id = 'book-1'"));
            assertEquals("sally", scalar(statement, "SELECT name_key FROM characters WHERE id = 'sally-winner'"));
            assertEquals(2, count(statement, """
                    SELECT COUNT(*) FROM character_chat_conversations
                    WHERE character_id = 'sally-winner'
                    """));
            assertEquals(0, count(statement, """
                    SELECT COUNT(*) FROM character_chat_conversations
                    WHERE character_id IN ('sally-pending', 'sally-case')
                    """));

            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "CHARACTERS", "NAME_KEY")) {
                assertTrue(columns.next());
                assertEquals(0, columns.getInt("NULLABLE"));
            }

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO characters
                        (id, book_id, character_type, created_at, first_chapter_id,
                         first_paragraph_index, name, name_key, retry_count, status)
                    VALUES
                        ('sally-again', 'book-1', 'SECONDARY', CURRENT_TIMESTAMP,
                         'chapter-1', 0, 'sally ', 'sally', 0, 'PENDING')
                    """));
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
