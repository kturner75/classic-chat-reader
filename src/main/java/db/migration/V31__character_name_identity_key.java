package db.migration;

import com.classicchatreader.service.CharacterNameNormalizer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adds a normalized {@code name_key} and collapses rows that are the same
 * character under case / punctuation / whitespace variants. Conversations on
 * losing rows are re-pointed to the winner before delete so chat history is
 * not cascade-dropped.
 */
public class V31__character_name_identity_key extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean mysqlFamily = isMysqlFamily(connection);

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE characters ADD COLUMN name_key VARCHAR(255)");
        }

        backfillNameKeys(connection);
        mergeDuplicateIdentities(connection);
        uniquifyBlankKeys(connection);

        try (Statement statement = connection.createStatement()) {
            if (mysqlFamily) {
                statement.execute("ALTER TABLE characters MODIFY name_key VARCHAR(255) NOT NULL");
            } else {
                statement.execute("ALTER TABLE characters ALTER COLUMN name_key SET NOT NULL");
            }
            statement.execute("CREATE UNIQUE INDEX uk_characters_book_name_key ON characters (book_id, name_key)");
        }
    }

    private static void backfillNameKeys(Connection connection) throws SQLException {
        List<NameRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT id, name FROM characters")) {
            while (result.next()) {
                rows.add(new NameRow(result.getString("id"), result.getString("name")));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE characters SET name_key = ? WHERE id = ?")) {
            for (NameRow row : rows) {
                String key = CharacterNameNormalizer.identityKey(row.name());
                if (key.isBlank()) {
                    key = CharacterNameNormalizer.fallbackKey(row.id());
                }
                update.setString(1, key);
                update.setString(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void mergeDuplicateIdentities(Connection connection) throws SQLException {
        Map<String, List<CharacterRow>> groups = new LinkedHashMap<>();
        String sql = """
                SELECT c.id, c.book_id, c.name, c.name_key, c.character_type, c.status,
                       c.portrait_filename, c.first_paragraph_index, c.created_at,
                       ch.chapter_index
                FROM characters c
                JOIN chapters ch ON ch.id = c.first_chapter_id
                """;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                CharacterRow row = CharacterRow.from(result);
                groups.computeIfAbsent(row.bookId() + "\u0000" + row.nameKey(), ignored -> new ArrayList<>())
                        .add(row);
            }
        }

        boolean hasDuplicates = groups.values().stream().anyMatch(group -> group.size() >= 2);
        if (hasDuplicates) {
            requireConversationsTable(connection);
        }
        for (List<CharacterRow> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(winnerOrder());
            CharacterRow winner = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                deleteLoserAfterSuccessfulRepoint(connection, group.get(i).id(), winner.id());
            }
        }
    }

    private static void uniquifyBlankKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id FROM characters WHERE name_key IS NULL OR TRIM(name_key) = ''");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE characters SET name_key = ? WHERE id = ?")) {
            while (result.next()) {
                String id = result.getString(1);
                update.setString(1, CharacterNameNormalizer.fallbackKey(id));
                update.setString(2, id);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void deleteLoserAfterSuccessfulRepoint(
            Connection connection, String loserId, String winnerId) throws SQLException {
        int conversationsBefore = countConversations(connection, loserId);
        int updated = repointConversations(connection, loserId, winnerId);
        int conversationsAfter = countConversations(connection, loserId);
        if (conversationsAfter != 0) {
            throw new SQLException("Refusing to delete character '" + loserId
                    + "': " + conversationsAfter
                    + " conversation(s) still point at it after re-point.");
        }
        if (conversationsBefore > 0 && updated < conversationsBefore) {
            throw new SQLException("Refusing to delete character '" + loserId
                    + "': re-point updated " + updated + " of " + conversationsBefore
                    + " conversation(s).");
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM characters WHERE id = ?")) {
            delete.setString(1, loserId);
            delete.executeUpdate();
        }
    }

    private static int repointConversations(Connection connection, String fromCharacterId, String toCharacterId)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE character_chat_conversations SET character_id = ? WHERE character_id = ?")) {
            update.setString(1, toCharacterId);
            update.setString(2, fromCharacterId);
            return update.executeUpdate();
        }
    }

    private static int countConversations(Connection connection, String characterId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM character_chat_conversations WHERE character_id = ?")) {
            query.setString(1, characterId);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void requireConversationsTable(Connection connection) throws SQLException {
        if (!conversationsTableReachable(connection)) {
            throw new SQLException(
                    "character_chat_conversations was not found; refusing to delete duplicate "
                            + "characters because chat rows would be cascade-deleted.");
        }
    }

    private static boolean conversationsTableReachable(Connection connection) {
        if (findTableIgnoreCase(connection, "character_chat_conversations") != null) {
            try {
                probeConversationsTable(connection);
                return true;
            } catch (SQLException ignored) {
                return false;
            }
        }
        try {
            probeConversationsTable(connection);
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static void probeConversationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery(
                     "SELECT 1 FROM character_chat_conversations WHERE 1 = 0")) {
            // Table is readable; Flyway will roll back if this probe or later DML fails.
        }
    }

    private static String findTableIgnoreCase(Connection connection, String tableName) {
        String catalog = null;
        String schema = null;
        try {
            catalog = connection.getCatalog();
        } catch (SQLException ignored) {
            // Catalog is optional for discovery.
        }
        try {
            schema = connection.getSchema();
        } catch (SQLException ignored) {
            // Schema is optional for discovery.
        }
        String found = scanTables(connection, catalog, schema, tableName);
        if (found != null) {
            return found;
        }
        return scanTables(connection, null, null, tableName);
    }

    private static String scanTables(Connection connection, String catalog, String schema, String tableName) {
        try (ResultSet tables = connection.getMetaData()
                .getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String actual = tables.getString("TABLE_NAME");
                if (actual != null && actual.equalsIgnoreCase(tableName)) {
                    return actual;
                }
            }
        } catch (SQLException ignored) {
            return null;
        }
        return null;
    }

    private static Comparator<CharacterRow> winnerOrder() {
        return Comparator
                .comparing(CharacterRow::primary, Comparator.reverseOrder())
                .thenComparing(CharacterRow::statusRank, Comparator.reverseOrder())
                .thenComparing(CharacterRow::hasPortrait, Comparator.reverseOrder())
                .thenComparingInt(CharacterRow::chapterIndex)
                .thenComparingInt(CharacterRow::paragraphIndex)
                .thenComparing(CharacterRow::createdAt)
                .thenComparing(CharacterRow::id);
    }

    private static boolean isMysqlFamily(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (product == null) {
            return false;
        }
        String lower = product.toLowerCase(Locale.ROOT);
        return lower.contains("mysql") || lower.contains("mariadb");
    }

    private record NameRow(String id, String name) {
    }

    private record CharacterRow(
            String id,
            String bookId,
            String name,
            String nameKey,
            boolean primary,
            int statusRank,
            boolean hasPortrait,
            int chapterIndex,
            int paragraphIndex,
            LocalDateTime createdAt
    ) {
        static CharacterRow from(ResultSet result) throws SQLException {
            String type = result.getString("character_type");
            String status = result.getString("status");
            String portrait = result.getString("portrait_filename");
            Timestamp created = result.getTimestamp("created_at");
            return new CharacterRow(
                    result.getString("id"),
                    result.getString("book_id"),
                    result.getString("name"),
                    result.getString("name_key"),
                    type != null && type.equalsIgnoreCase("PRIMARY"),
                    statusRank(status),
                    portrait != null && !portrait.isBlank(),
                    result.getInt("chapter_index"),
                    result.getInt("first_paragraph_index"),
                    created == null ? LocalDateTime.MAX : created.toLocalDateTime()
            );
        }

        private static int statusRank(String status) {
            if (status == null) {
                return 0;
            }
            return switch (status.toUpperCase(Locale.ROOT)) {
                case "COMPLETED" -> 4;
                case "GENERATING" -> 3;
                case "PENDING" -> 2;
                case "FAILED" -> 1;
                default -> 0;
            };
        }
    }
}
