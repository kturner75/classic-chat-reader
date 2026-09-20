package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * BL-043.6: FERPA access logs and chat export records must outlive account deletion. Deleting an
 * account rewrites their user ids to a pseudonym ("deleted:<hash>"), which foreign keys to users
 * would reject. Drop those four constraints; the columns stay NOT NULL.
 * Java migration because MariaDB spells it DROP FOREIGN KEY.
 */
public class V33__audit_rows_outlive_accounts extends BaseJavaMigration {

    private static final String[][] CONSTRAINTS = {
            {"education_record_access_logs", "fk_eral_actor"},
            {"education_record_access_logs", "fk_eral_subject"},
            {"chat_export_jobs", "fk_cej_requester"},
            {"chat_export_jobs", "fk_cej_subject"},
    };

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        boolean mysqlFamily = product != null
                && (product.toLowerCase().contains("mysql") || product.toLowerCase().contains("mariadb"));
        try (Statement statement = connection.createStatement()) {
            for (String[] constraint : CONSTRAINTS) {
                statement.execute("ALTER TABLE " + constraint[0]
                        + (mysqlFamily ? " DROP FOREIGN KEY " : " DROP CONSTRAINT ") + constraint[1]);
            }
        }
    }
}
