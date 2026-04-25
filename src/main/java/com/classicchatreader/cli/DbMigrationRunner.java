package com.classicchatreader.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * One-time data migration utility to copy persisted reader data from an H2 source
 * database to a target JDBC database (PostgreSQL or MariaDB).
 */
public class DbMigrationRunner {

    private static final String APPLY_FLAG = "--apply";
    private static final String TRUNCATE_TARGET_FLAG = "--truncate-target";
    private static final String HELP_FLAG = "--help";

    private static final String SOURCE_URL_FLAG = "--source-url";
    private static final String SOURCE_USER_FLAG = "--source-user";
    private static final String SOURCE_PASSWORD_FLAG = "--source-password";

    private static final String TARGET_URL_FLAG = "--target-url";
    private static final String TARGET_USER_FLAG = "--target-user";
    private static final String TARGET_PASSWORD_FLAG = "--target-password";

    private static final Set<String> VALUE_OPTIONS = Set.of(
            SOURCE_URL_FLAG, SOURCE_USER_FLAG, SOURCE_PASSWORD_FLAG,
            TARGET_URL_FLAG, TARGET_USER_FLAG, TARGET_PASSWORD_FLAG
    );

    // Parent tables first; child tables later for FK-safe inserts.
    private static final List<String> TABLE_ORDER = List.of(
            "books",
            "chapters",
            "paragraphs",
            "illustrations",
            "chapter_analyses",
            "characters",
            "chapter_recaps",
            "chapter_quizzes",
            "quiz_attempts",
            "quiz_trophies"
    );

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ParsedArgs parsed;
        try {
            parsed = parseArgs(args);
        } catch (IllegalArgumentException e) {
            err.println("Argument error: " + e.getMessage());
            printUsage(err);
            return 1;
        }

        if (parsed.flags().contains(HELP_FLAG)) {
            printUsage(out);
            return 0;
        }

        boolean apply = parsed.flags().contains(APPLY_FLAG);
        boolean truncateTarget = parsed.flags().contains(TRUNCATE_TARGET_FLAG);
        if (truncateTarget && !apply) {
            err.println("--truncate-target requires --apply.");
            return 1;
        }

        Properties properties = loadProperties();
        DbConfig source = resolveSourceConfig(parsed, properties);
        DbConfig target = resolveTargetConfig(parsed);

        if (target.url() == null || target.url().isBlank()) {
            err.println("Missing target DB URL. Provide --target-url or set MIGRATION_TARGET_URL/SPRING_DATASOURCE_URL.");
            return 1;
        }
        if (source.url() == null || source.url().isBlank()) {
            err.println("Missing source DB URL.");
            return 1;
        }
        if (source.url().equals(target.url())) {
            err.println("Source and target DB URLs are identical; refusing to run.");
            return 1;
        }

        out.println("Source URL: " + source.url());
        out.println("Target URL: " + target.url());
        out.println("Mode: " + (apply ? "APPLY" : "DRY-RUN"));
        if (truncateTarget) {
            out.println("Target pre-step: truncate enabled");
        }

        try (Connection sourceConnection = DriverManager.getConnection(source.url(), source.user(), source.password());
             Connection targetConnection = DriverManager.getConnection(target.url(), target.user(), target.password())) {
            validateSchema(sourceConnection, "source");
            validateSchema(targetConnection, "target");

            Map<String, Integer> sourceCounts = countRowsByTable(sourceConnection);
            Map<String, Integer> targetCounts = countRowsByTable(targetConnection);
            printTableCounts("Source", sourceCounts, out);
            printTableCounts("Target", targetCounts, out);

            if (!apply) {
                out.println("Dry-run complete; no rows copied.");
                return 0;
            }

            if (!truncateTarget) {
                for (String table : TABLE_ORDER) {
                    if (targetCounts.getOrDefault(table, 0) > 0) {
                        err.println("Target table '" + table + "' is not empty (" + targetCounts.get(table)
                                + " rows). Re-run with --truncate-target or empty the target DB.");
                        return 1;
                    }
                }
            }

            targetConnection.setAutoCommit(false);
            try {
                if (truncateTarget) {
                    truncateTargetTables(targetConnection, out);
                }

                Map<String, Integer> inserted = new LinkedHashMap<>();
                for (String table : TABLE_ORDER) {
                    int insertedRows = copyTable(table, sourceConnection, targetConnection);
                    inserted.put(table, insertedRows);
                }
                targetConnection.commit();

                out.println("Migration applied successfully.");
                printTableCounts("Inserted", inserted, out);
                return 0;
            } catch (Exception e) {
                targetConnection.rollback();
                throw e;
            }
        } catch (Exception e) {
            err.println("DB migration failed: " + e.getMessage());
            return 1;
        }
    }

    private static ParsedArgs parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> flags = new java.util.LinkedHashSet<>();
        List<String> tokens = Arrays.asList(args);

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected positional argument: " + token);
            }
            if (APPLY_FLAG.equals(token) || TRUNCATE_TARGET_FLAG.equals(token) || HELP_FLAG.equals(token)) {
                flags.add(token);
                continue;
            }
            if (!VALUE_OPTIONS.contains(token)) {
                throw new IllegalArgumentException("Unknown option: " + token);
            }
            if (i + 1 >= tokens.size()) {
                throw new IllegalArgumentException("Missing value for option: " + token);
            }
            String value = tokens.get(++i);
            options.put(token, value);
        }
        return new ParsedArgs(options, flags);
    }

    private static DbConfig resolveSourceConfig(ParsedArgs parsed, Properties properties) {
        String url = firstNonBlank(
                parsed.optionValue(SOURCE_URL_FLAG).orElse(null),
                System.getenv("MIGRATION_SOURCE_URL"),
                properties.getProperty("spring.datasource.url"),
                "jdbc:h2:file:./data/library"
        );
        String user = firstNonBlank(
                parsed.optionValue(SOURCE_USER_FLAG).orElse(null),
                System.getenv("MIGRATION_SOURCE_USERNAME"),
                properties.getProperty("spring.datasource.username"),
                "sa"
        );
        String password = firstNonBlank(
                parsed.optionValue(SOURCE_PASSWORD_FLAG).orElse(null),
                System.getenv("MIGRATION_SOURCE_PASSWORD"),
                properties.getProperty("spring.datasource.password"),
                ""
        );
        return new DbConfig(normalizeDbUrl(url), user, password);
    }

    private static DbConfig resolveTargetConfig(ParsedArgs parsed) {
        String url = firstNonBlank(
                parsed.optionValue(TARGET_URL_FLAG).orElse(null),
                System.getenv("MIGRATION_TARGET_URL"),
                System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("DATABASE_URL")
        );
        String user = firstNonBlank(
                parsed.optionValue(TARGET_USER_FLAG).orElse(null),
                System.getenv("MIGRATION_TARGET_USERNAME"),
                System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("DATABASE_USERNAME"),
                ""
        );
        String password = firstNonBlank(
                parsed.optionValue(TARGET_PASSWORD_FLAG).orElse(null),
                System.getenv("MIGRATION_TARGET_PASSWORD"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"),
                System.getenv("DATABASE_PASSWORD"),
                ""
        );
        return new DbConfig(normalizeDbUrl(url), user, password);
    }

    static String normalizeDbUrl(String dbUrl) {
        if (dbUrl == null) {
            return null;
        }
        String trimmed = dbUrl.trim();
        if (!trimmed.startsWith("jdbc:h2:")) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("db_close_on_exit=")) {
            return trimmed;
        }
        return trimmed + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private static void validateSchema(Connection connection, String label) throws SQLException {
        for (String table : TABLE_ORDER) {
            if (!tableExists(connection, table)) {
                throw new SQLException(label + " database missing table: " + table);
            }
        }
    }

    private static Map<String, Integer> countRowsByTable(Connection connection) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : TABLE_ORDER) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
                if (rs.next()) {
                    counts.put(table, rs.getInt(1));
                }
            }
        }
        return counts;
    }

    private static void printTableCounts(String label, Map<String, Integer> counts, PrintStream out) {
        out.println(label + " table counts:");
        for (String table : TABLE_ORDER) {
            out.println("  " + table + ": " + counts.getOrDefault(table, 0));
        }
    }

    private static void truncateTargetTables(Connection targetConnection, PrintStream out) throws SQLException {
        List<String> reverseOrder = new ArrayList<>(TABLE_ORDER);
        Collections.reverse(reverseOrder);
        for (String table : reverseOrder) {
            try (Statement statement = targetConnection.createStatement()) {
                statement.executeUpdate("delete from " + table);
                out.println("Truncated target table: " + table);
            }
        }
    }

    private static int copyTable(String table, Connection sourceConnection, Connection targetConnection) throws SQLException {
        String query = "select * from " + table;
        try (Statement sourceStatement = sourceConnection.createStatement();
             ResultSet rs = sourceStatement.executeQuery(query)) {
            ResultSetMetaData meta = rs.getMetaData();
            String insertSql = buildInsertSql(table, meta);

            int batchSize = 0;
            int inserted = 0;
            try (PreparedStatement insert = targetConnection.prepareStatement(insertSql)) {
                while (rs.next()) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        insert.setObject(i, normalizedValue(rs.getObject(i)));
                    }
                    insert.addBatch();
                    batchSize++;
                    inserted++;

                    if (batchSize >= 500) {
                        insert.executeBatch();
                        batchSize = 0;
                    }
                }
                if (batchSize > 0) {
                    insert.executeBatch();
                }
            }
            return inserted;
        }
    }

    private static Object normalizedValue(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            return clob.getSubString(1, (int) clob.length());
        }
        return value;
    }

    private static String buildInsertSql(String table, ResultSetMetaData meta) throws SQLException {
        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (i > 1) {
                columns.append(", ");
                values.append(", ");
            }
            columns.append(meta.getColumnName(i));
            values.append("?");
        }
        return "insert into " + table + " (" + columns + ") values (" + values + ")";
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return hasTable(metaData, tableName)
                || hasTable(metaData, tableName.toLowerCase())
                || hasTable(metaData, tableName.toUpperCase());
    }

    private static boolean hasTable(DatabaseMetaData metaData, String tableName) throws SQLException {
        try (ResultSet tables = metaData.getTables(null, null, tableName, null)) {
            return tables.next();
        }
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = DbMigrationRunner.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
                return properties;
            }
        } catch (IOException ignored) {
            // Best-effort fallback below.
        }
        Path fallbackPath = Path.of("src/main/resources/application.properties");
        if (Files.exists(fallbackPath)) {
            try (InputStream input = Files.newInputStream(fallbackPath)) {
                properties.load(input);
            } catch (IOException ignored) {
                // Continue with defaults.
            }
        }
        return properties;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: DbMigrationRunner [options]");
        out.println("  --apply                    Execute migration (default is dry-run).");
        out.println("  --truncate-target          Delete all target rows before copy (FK-safe order).");
        out.println("  --source-url <jdbc-url>    Source DB URL (default: local H2).");
        out.println("  --source-user <username>   Source DB username.");
        out.println("  --source-password <pass>   Source DB password.");
        out.println("  --target-url <jdbc-url>    Target DB URL (required unless env is set).");
        out.println("  --target-user <username>   Target DB username.");
        out.println("  --target-password <pass>   Target DB password.");
        out.println("  --help                     Show this help.");
        out.println();
        out.println("Environment fallbacks:");
        out.println("  Source: MIGRATION_SOURCE_URL / *_USERNAME / *_PASSWORD then application.properties");
        out.println("  Target: MIGRATION_TARGET_URL / *_USERNAME / *_PASSWORD,");
        out.println("          then SPRING_DATASOURCE_URL / *_USERNAME / *_PASSWORD");
    }

    private record ParsedArgs(Map<String, String> options, Set<String> flags) {
        private Optional<String> optionValue(String option) {
            return Optional.ofNullable(options.get(option));
        }
    }

    private record DbConfig(String url, String user, String password) {
    }
}
