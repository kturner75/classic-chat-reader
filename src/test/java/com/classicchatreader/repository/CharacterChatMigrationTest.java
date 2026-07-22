package com.classicchatreader.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterChatMigrationTest {

    @Test
    void migrationAppliesEnforcesOwnershipAndOrderAndRollsBack() throws Exception {
        String url = "jdbc:h2:mem:character-chat-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        flyway.migrate();
        assertEquals("18", flyway.info().current().getVersion().getVersion());

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            seedOwnersAndCharacter(statement);

            statement.executeUpdate("""
                    INSERT INTO character_chat_conversations
                        (id, user_id, character_id, created_at, updated_at)
                    VALUES
                        ('conversation-1', 'user-a', 'character-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('conversation-2', 'user-a', 'character-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('conversation-3', 'user-b', 'character-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);

            assertEquals(2, count(statement, """
                    SELECT COUNT(*) FROM character_chat_conversations
                    WHERE user_id = 'user-a' AND character_id = 'character-1'
                    """));

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO character_chat_messages
                        (id, conversation_id, user_id, sequence_number, role, content, created_at)
                    VALUES
                        ('wrong-owner', 'conversation-1', 'user-b', 0, 'USER', 'Not allowed', CURRENT_TIMESTAMP)
                    """));

            statement.executeUpdate("""
                    INSERT INTO character_chat_messages
                        (id, conversation_id, user_id, sequence_number, role, content, created_at)
                    VALUES
                        ('message-2', 'conversation-1', 'user-a', 2, 'CHARACTER', 'Second', CURRENT_TIMESTAMP),
                        ('message-1', 'conversation-1', 'user-a', 1, 'USER', 'First', CURRENT_TIMESTAMP)
                    """);

            try (ResultSet rows = statement.executeQuery("""
                    SELECT sequence_number FROM character_chat_messages
                    WHERE conversation_id = 'conversation-1' AND user_id = 'user-a'
                    ORDER BY sequence_number
                    """)) {
                assertTrue(rows.next());
                assertEquals(1L, rows.getLong(1));
                assertTrue(rows.next());
                assertEquals(2L, rows.getLong(1));
                assertFalse(rows.next());
            }

            assertTrue(indexExists(
                    connection,
                    "CHARACTER_CHAT_CONVERSATIONS",
                    "IDX_CCC_USER_CHARACTER_UPDATED"));
            assertTrue(indexExists(
                    connection,
                    "CHARACTER_CHAT_MESSAGES",
                    "IDX_CCM_CONVERSATION_USER_SEQUENCE"));

            statement.executeUpdate("DELETE FROM character_chat_conversations WHERE id = 'conversation-1'");
            assertEquals(0, count(statement, """
                    SELECT COUNT(*) FROM character_chat_messages WHERE conversation_id = 'conversation-1'
                    """));

            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/rollback/U18__character_chat_resume_context.sql"));
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/rollback/U17__character_chat_persistence.sql"));
            assertFalse(tableExists(connection, "CHARACTER_CHAT_MESSAGES"));
            assertFalse(tableExists(connection, "CHARACTER_CHAT_CONVERSATIONS"));
        }
    }

    private void seedOwnersAndCharacter(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO users (id, email, created_at, updated_at) VALUES
                    ('user-a', 'a@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    ('user-b', 'b@example.test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        statement.executeUpdate("""
                INSERT INTO books (id, title, author, source)
                VALUES ('book-1', 'Pride', 'Austen', 'manual')
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
                    ('character-1', 'book-1', 'PRIMARY', CURRENT_TIMESTAMP, 'chapter-1',
                     0, 'Elizabeth', 0, 'COMPLETED')
                """);
    }

    private long count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        try (ResultSet indexes = connection.getMetaData()
                .getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                String actual = indexes.getString("INDEX_NAME");
                if (actual != null && indexName.equals(actual.toUpperCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
