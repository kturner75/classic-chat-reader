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

        boolean conversationsExist = tableExists(connection, "character_chat_conversations");
        for (List<CharacterRow> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(winnerOrder());
            CharacterRow winner = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                CharacterRow loser = group.get(i);
                if (conversationsExist) {
                    repointConversations(connection, loser.id(), winner.id());
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM characters WHERE id = ?")) {
                    delete.setString(1, loser.id());
                    delete.executeUpdate();
                }
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

    private static void repointConversations(Connection connection, String fromCharacterId, String toCharacterId)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE character_chat_conversations SET character_id = ? WHERE character_id = ?")) {
            update.setString(1, toCharacterId);
            update.setString(2, fromCharacterId);
            update.executeUpdate();
        }
    }

    private static Comparator<CharacterRow> winnerOrder() {
        return Comparator
                .comparing(CharacterRow::primary).reversed()
                .thenComparing(CharacterRow::statusRank).reversed()
                .thenComparing(CharacterRow::hasPortrait).reversed()
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

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData()
                .getTables(null, null, tableName, new String[]{"TABLE"})) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = connection.getMetaData()
                .getTables(null, null, tableName.toUpperCase(Locale.ROOT), new String[]{"TABLE"})) {
            return tables.next();
        }
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
