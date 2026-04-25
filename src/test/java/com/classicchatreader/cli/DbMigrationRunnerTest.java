package com.classicchatreader.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbMigrationRunnerTest {

    @Test
    void dryRun_doesNotMutateTarget() throws Exception {
        String sourceUrl = "jdbc:h2:mem:db_migration_source_dry;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:db_migration_target_dry;DB_CLOSE_DELAY=-1";
        createSchema(sourceUrl);
        createSchema(targetUrl);
        seedSourceData(sourceUrl);

        RunResult result = runCli(new String[]{
                "--source-url", sourceUrl,
                "--target-url", targetUrl,
                "--target-user", "sa"
        });

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Dry-run complete"));
        assertEquals(0, countRows(targetUrl, "books"));
    }

    @Test
    void apply_copiesAllTablesInDependencyOrder() throws Exception {
        String sourceUrl = "jdbc:h2:mem:db_migration_source_apply;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:db_migration_target_apply;DB_CLOSE_DELAY=-1";
        createSchema(sourceUrl);
        createSchema(targetUrl);
        seedSourceData(sourceUrl);

        RunResult result = runCli(new String[]{
                "--apply",
                "--source-url", sourceUrl,
                "--target-url", targetUrl,
                "--target-user", "sa"
        });

        assertEquals(0, result.exitCode());
        assertEquals(1, countRows(targetUrl, "books"));
        assertEquals(1, countRows(targetUrl, "chapters"));
        assertEquals(1, countRows(targetUrl, "paragraphs"));
        assertEquals(1, countRows(targetUrl, "illustrations"));
        assertEquals(1, countRows(targetUrl, "chapter_analyses"));
        assertEquals(1, countRows(targetUrl, "characters"));
        assertEquals(1, countRows(targetUrl, "chapter_recaps"));
        assertEquals(1, countRows(targetUrl, "chapter_quizzes"));
        assertEquals(1, countRows(targetUrl, "quiz_attempts"));
        assertEquals(1, countRows(targetUrl, "quiz_trophies"));
    }

    @Test
    void apply_failsWhenTargetHasExistingRowsWithoutTruncate() throws Exception {
        String sourceUrl = "jdbc:h2:mem:db_migration_source_nonempty;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:db_migration_target_nonempty;DB_CLOSE_DELAY=-1";
        createSchema(sourceUrl);
        createSchema(targetUrl);
        seedSourceData(sourceUrl);
        seedSourceData(targetUrl);

        RunResult result = runCli(new String[]{
                "--apply",
                "--source-url", sourceUrl,
                "--target-url", targetUrl,
                "--target-user", "sa"
        });

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("is not empty"));
    }

    private static RunResult runCli(String[] args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(outBuffer);
             PrintStream err = new PrintStream(errBuffer)) {
            int exitCode = DbMigrationRunner.run(args, out, err);
            return new RunResult(exitCode, outBuffer.toString(), errBuffer.toString());
        }
    }

    private static void createSchema(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table books (
                        id varchar(255) primary key,
                        author varchar(255) not null,
                        source varchar(255) not null,
                        source_id varchar(255),
                        title varchar(255) not null
                    )
                    """);
            statement.execute("""
                    create table chapters (
                        id varchar(255) primary key,
                        book_id varchar(255) not null,
                        chapter_index integer not null,
                        title varchar(255) not null,
                        constraint fk_chapters_book foreign key (book_id) references books (id)
                    )
                    """);
            statement.execute("""
                    create table paragraphs (
                        id varchar(255) primary key,
                        chapter_id varchar(255) not null,
                        paragraph_index integer not null,
                        content clob not null,
                        constraint fk_paragraphs_chapter foreign key (chapter_id) references chapters (id)
                    )
                    """);
            statement.execute("""
                    create table illustrations (
                        id varchar(255) primary key,
                        chapter_id varchar(255) not null,
                        created_at timestamp not null,
                        retry_count integer not null default 0,
                        status varchar(64) not null,
                        constraint uk_illustrations_chapter unique (chapter_id),
                        constraint fk_illustrations_chapter foreign key (chapter_id) references chapters (id)
                    )
                    """);
            statement.execute("""
                    create table chapter_analyses (
                        id varchar(255) primary key,
                        chapter_id varchar(255) not null,
                        analyzed_at timestamp not null,
                        character_count integer not null,
                        retry_count integer not null default 0,
                        status varchar(64),
                        constraint uk_chapter_analyses_chapter unique (chapter_id),
                        constraint fk_chapter_analyses_chapter foreign key (chapter_id) references chapters (id)
                    )
                    """);
            statement.execute("""
                    create table characters (
                        id varchar(255) primary key,
                        book_id varchar(255) not null,
                        character_type varchar(255) not null default 'SECONDARY',
                        created_at timestamp not null,
                        first_chapter_id varchar(255) not null,
                        first_paragraph_index integer not null,
                        name varchar(255) not null,
                        retry_count integer not null default 0,
                        status varchar(64) not null,
                        constraint uk_characters_book_name unique (book_id, name),
                        constraint fk_characters_book foreign key (book_id) references books (id),
                        constraint fk_characters_first_chapter foreign key (first_chapter_id) references chapters (id)
                    )
                    """);
            statement.execute("""
                    create table chapter_recaps (
                        id varchar(255) primary key,
                        chapter_id varchar(255) not null,
                        payload_json clob,
                        retry_count integer not null default 0,
                        status varchar(64) not null,
                        updated_at timestamp not null,
                        constraint uk_chapter_recaps_chapter unique (chapter_id),
                        constraint fk_chapter_recaps_chapter foreign key (chapter_id) references chapters (id)
                    )
                    """);
            statement.execute("""
                    create table chapter_quizzes (
                        id varchar(255) primary key,
                        chapter_id varchar(255) not null,
                        payload_json clob,
                        status varchar(64) not null,
                        updated_at timestamp not null,
                        constraint uk_chapter_quizzes_chapter unique (chapter_id),
                        constraint fk_chapter_quizzes_chapter foreign key (chapter_id) references chapters (id)
                    )
                    """);
            statement.execute("""
                    create table quiz_attempts (
                        id varchar(255) primary key,
                        chapter_id varchar(255) not null,
                        correct_answers integer not null,
                        created_at timestamp not null,
                        difficulty_level integer not null,
                        perfect boolean not null,
                        score_percent integer not null,
                        total_questions integer not null,
                        constraint fk_quiz_attempts_chapter foreign key (chapter_id) references chapters (id)
                    )
                    """);
            statement.execute("""
                    create table quiz_trophies (
                        id varchar(255) primary key,
                        book_id varchar(255) not null,
                        code varchar(64) not null,
                        description varchar(400) not null,
                        title varchar(120) not null,
                        unlocked_at timestamp not null,
                        constraint uk_quiz_trophies_book_code unique (book_id, code),
                        constraint fk_quiz_trophies_book foreign key (book_id) references books (id)
                    )
                    """);
        }
    }

    private static void seedSourceData(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into books (id, author, source, source_id, title)
                    values ('book-1', 'Jane Austen', 'gutenberg', '1342', 'Pride and Prejudice')
                    """);
            statement.execute("""
                    insert into chapters (id, book_id, chapter_index, title)
                    values ('chapter-1', 'book-1', 0, 'Chapter 1')
                    """);
            statement.execute("""
                    insert into paragraphs (id, chapter_id, paragraph_index, content)
                    values ('paragraph-1', 'chapter-1', 0, 'It is a truth universally acknowledged...')
                    """);
            statement.execute("""
                    insert into illustrations (id, chapter_id, created_at, retry_count, status)
                    values ('illustration-1', 'chapter-1', TIMESTAMP '2026-02-14 10:00:00', 0, 'COMPLETED')
                    """);
            statement.execute("""
                    insert into chapter_analyses (id, chapter_id, analyzed_at, character_count, retry_count, status)
                    values ('analysis-1', 'chapter-1', TIMESTAMP '2026-02-14 10:01:00', 2, 0, 'COMPLETED')
                    """);
            statement.execute("""
                    insert into characters (id, book_id, character_type, created_at, first_chapter_id, first_paragraph_index, name, retry_count, status)
                    values ('character-1', 'book-1', 'PRIMARY', TIMESTAMP '2026-02-14 10:02:00', 'chapter-1', 0, 'Elizabeth Bennet', 0, 'COMPLETED')
                    """);
            statement.execute("""
                    insert into chapter_recaps (id, chapter_id, payload_json, retry_count, status, updated_at)
                    values ('recap-1', 'chapter-1', '{"shortSummary":"Summary"}', 0, 'COMPLETED', TIMESTAMP '2026-02-14 10:03:00')
                    """);
            statement.execute("""
                    insert into chapter_quizzes (id, chapter_id, payload_json, status, updated_at)
                    values ('quiz-1', 'chapter-1', '{"questions":[]}', 'COMPLETED', TIMESTAMP '2026-02-14 10:03:30')
                    """);
            statement.execute("""
                    insert into quiz_attempts (id, chapter_id, correct_answers, created_at, difficulty_level, perfect, score_percent, total_questions)
                    values ('attempt-1', 'chapter-1', 3, TIMESTAMP '2026-02-14 10:04:00', 1, true, 100, 3)
                    """);
            statement.execute("""
                    insert into quiz_trophies (id, book_id, code, description, title, unlocked_at)
                    values ('trophy-1', 'book-1', 'first_perfect', 'First perfect quiz score', 'Perfect Start', TIMESTAMP '2026-02-14 10:05:00')
                    """);
        }
    }

    private static int countRows(String dbUrl, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
    }
}
