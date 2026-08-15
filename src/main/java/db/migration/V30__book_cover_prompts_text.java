package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Cover prompts are full image briefs, not 2000-char titles. TEXT on MariaDB
 * and H2/Postgres so generation never has to clip.
 */
public class V30__book_cover_prompts_text extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        boolean mysqlFamily = product != null
                && (product.toLowerCase().contains("mysql") || product.toLowerCase().contains("mariadb"));
        String generated = mysqlFamily
                ? "ALTER TABLE book_covers MODIFY generated_prompt TEXT"
                : "ALTER TABLE book_covers ALTER COLUMN generated_prompt SET DATA TYPE TEXT";
        String override = mysqlFamily
                ? "ALTER TABLE book_covers MODIFY prompt_override TEXT"
                : "ALTER TABLE book_covers ALTER COLUMN prompt_override SET DATA TYPE TEXT";
        String coverFocus = mysqlFamily
                ? "ALTER TABLE books MODIFY illustration_cover_focus TEXT"
                : "ALTER TABLE books ALTER COLUMN illustration_cover_focus SET DATA TYPE TEXT";
        try (Statement statement = connection.createStatement()) {
            statement.execute(generated);
            statement.execute(override);
            statement.execute(coverFocus);
        }
    }
}
