package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Allow assignment-only quiz attempts (no chapter_id) on both PostgreSQL and MariaDB.
 * V25 cannot use {@code ALTER COLUMN ... DROP NOT NULL} because MariaDB rejects that syntax.
 */
public class V27__quiz_attempts_chapter_id_nullable extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        String sql = product != null && product.toLowerCase().contains("mysql")
                || product != null && product.toLowerCase().contains("mariadb")
                ? "ALTER TABLE quiz_attempts MODIFY chapter_id VARCHAR(255) NULL"
                : "ALTER TABLE quiz_attempts ALTER COLUMN chapter_id DROP NOT NULL";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
