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
                    INSERT INTO characters
                        (id, book_id, character_type, created_at, first_chapter_id,
                         first_paragraph_index, name, retry_count, status)
                    VALUES
                        ('mrs-bennet', 'book-1', 'PRIMARY', TIMESTAMP '2026-08-01 09:00:00',
                         'chapter-1', 0, 'Mrs. Bennet', 0, 'COMPLETED'),
                        ('elizabeth-bennet', 'book-1', 'PRIMARY', TIMESTAMP '2026-08-01 09:05:00',
                         'chapter-1', 1, 'Elizabeth Bennet', 0, 'COMPLETED'),
                        ('mr-allen', 'book-1', 'SECONDARY', TIMESTAMP '2026-08-01 09:10:00',
                         'chapter-1', 2, 'Mr. Allen', 0, 'COMPLETED'),
                        ('mrs-allen', 'book-1', 'SECONDARY', TIMESTAMP '2026-08-01 09:15:00',
                         'chapter-1', 3, 'Mrs. Allen', 0, 'COMPLETED')
                    """);
            statement.executeUpdate("""
                    INSERT INTO character_chat_conversations
                        (id, user_id, character_id, created_at, updated_at)
                    VALUES
                        ('chat-pending', 'user-1', 'sally-pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('chat-case', 'user-1', 'sally-case', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('chat-elizabeth', 'user-1', 'elizabeth-bennet', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO character_chat_messages
                        (id, conversation_id, user_id, sequence_number, role, content, created_at)
                    VALUES
                        ('msg-pending', 'chat-pending', 'user-1', 0, 'USER', 'Hello Sally', CURRENT_TIMESTAMP),
                        ('msg-case', 'chat-case', 'user-1', 0, 'CHARACTER', 'It is I', CURRENT_TIMESTAMP),
                        ('msg-elizabeth', 'chat-elizabeth', 'user-1', 0, 'USER', 'Hello Lizzy', CURRENT_TIMESTAMP)
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
            assertEquals(5, count(statement, "SELECT COUNT(*) FROM characters WHERE book_id = 'book-1'"));
            assertEquals("sally-winner", scalar(statement, """
                    SELECT id FROM characters WHERE book_id = 'book-1' AND name_key = 'sally'
                    """));
            assertEquals("sally", scalar(statement, "SELECT name_key FROM characters WHERE id = 'sally-winner'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM characters WHERE id = 'mrs-bennet'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM characters WHERE id = 'elizabeth-bennet'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM characters WHERE id = 'mr-allen'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM characters WHERE id = 'mrs-allen'"));
            assertEquals(2, count(statement, """
                    SELECT COUNT(*) FROM character_chat_conversations
                    WHERE character_id = 'sally-winner'
                    """));
            assertEquals(0, count(statement, """
                    SELECT COUNT(*) FROM character_chat_conversations
                    WHERE character_id IN ('sally-pending', 'sally-case')
                    """));
            assertEquals(1, count(statement, """
                    SELECT COUNT(*) FROM character_chat_conversations
                    WHERE id = 'chat-elizabeth' AND character_id = 'elizabeth-bennet'
                    """));
            assertEquals(3, count(statement, "SELECT COUNT(*) FROM character_chat_messages"));
            assertEquals(2, count(statement, """
                    SELECT COUNT(*) FROM character_chat_messages
                    WHERE conversation_id IN ('chat-pending', 'chat-case')
                    """));
            assertEquals("Hello Sally", scalar(statement, """
                    SELECT content FROM character_chat_messages WHERE id = 'msg-pending'
                    """));
            assertEquals("It is I", scalar(statement, """
                    SELECT content FROM character_chat_messages WHERE id = 'msg-case'
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

    @Test
    void refusesToDeleteDuplicatesWhenConversationsTableIsUnreachable() throws Exception {
        String url = "jdbc:h2:mem:character-name-identity-failsafe-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway toV30 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("30")
                .cleanDisabled(false)
                .load();
        toV30.clean();
        toV30.migrate();

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
                         first_paragraph_index, name, retry_count, status)
                    VALUES
                        ('sally-a', 'book-1', 'SECONDARY', TIMESTAMP '2026-08-01 10:00:00',
                         'chapter-1', 1, 'Sally', 0, 'PENDING'),
                        ('sally-b', 'book-1', 'SECONDARY', TIMESTAMP '2026-08-01 11:00:00',
                         'chapter-1', 2, 'Sally.', 0, 'COMPLETED')
                    """);
            statement.executeUpdate("DROP TABLE character_chat_messages");
            statement.executeUpdate("DROP TABLE character_chat_conversations");
        }

        Flyway toV31 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        assertThrows(Exception.class, toV31::migrate);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(2, count(statement, "SELECT COUNT(*) FROM characters WHERE book_id = 'book-1'"));
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
