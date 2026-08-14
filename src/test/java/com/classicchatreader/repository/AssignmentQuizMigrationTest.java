package com.classicchatreader.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentQuizMigrationTest {

    @Test
    void migratesThroughAssignmentQuizzesAndNullableChapterAttempts() throws Exception {
        String url = "jdbc:h2:mem:assignment-quiz-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        flyway.validate();
        assertEquals("27", flyway.info().current().getVersion().getVersion());

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "QUIZ_ATTEMPTS", "CHAPTER_ID")) {
                assertTrue(columns.next());
                assertEquals(1, columns.getInt("NULLABLE"));
            }
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "QUIZ_ATTEMPTS", "ASSIGNMENT_ID")) {
                assertTrue(columns.next());
            }
        }
    }
}
