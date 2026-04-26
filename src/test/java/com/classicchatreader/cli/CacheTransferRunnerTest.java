package com.classicchatreader.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheTransferRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void exportApplyAllCached_writesRecapBundleJson() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_export;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedExportData(dbUrl);

        Path output = tempDir.resolve("recaps-export.json");
        RunResult result = runCli(new String[]{
                "export",
                "--feature", "recaps",
                "--all-cached",
                "--apply",
                "--output", output.toString(),
                "--db-url", dbUrl
        });

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(output));

        JsonNode root = OBJECT_MAPPER.readTree(output.toFile());
        assertEquals("1.0", root.get("formatVersion").asText());
        assertEquals("recaps", root.get("features").get(0).asText());
        assertEquals(1, root.get("books").size());
        assertEquals("gutenberg", root.get("books").get(0).get("source").asText());
        assertEquals("1342", root.get("books").get(0).get("sourceId").asText());
        assertEquals(1, root.get("books").get(0).get("recaps").size());
        assertEquals(0, root.get("books").get(0).get("recaps").get(0).get("chapterIndex").asInt());
        assertEquals("COMPLETED", root.get("books").get(0).get("recaps").get(0).get("status").asText());
    }

    @Test
    void exportApplyAllCached_writesQuizBundleJson() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_export_quizzes;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedExportData(dbUrl);

        Path output = tempDir.resolve("quizzes-export.json");
        RunResult result = runCli(new String[]{
                "export",
                "--feature", "quizzes",
                "--all-cached",
                "--apply",
                "--output", output.toString(),
                "--db-url", dbUrl
        });

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(output));

        JsonNode root = OBJECT_MAPPER.readTree(output.toFile());
        assertEquals("1.0", root.get("formatVersion").asText());
        assertEquals("quizzes", root.get("features").get(0).asText());
        assertEquals(1, root.get("books").size());
        assertEquals("gutenberg", root.get("books").get(0).get("source").asText());
        assertEquals("1342", root.get("books").get(0).get("sourceId").asText());
        assertEquals(1, root.get("books").get(0).get("quizzes").size());
        assertEquals(0, root.get("books").get(0).get("quizzes").get(0).get("chapterIndex").asInt());
        assertEquals("COMPLETED", root.get("books").get(0).get("quizzes").get(0).get("status").asText());
    }

    @Test
    void exportApplyAllCached_writesIllustrationBundleJson() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_export_illustrations;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedExportData(dbUrl);

        Path output = tempDir.resolve("illustrations-export.json");
        RunResult result = runCli(new String[]{
                "export",
                "--feature", "illustrations",
                "--all-cached",
                "--apply",
                "--output", output.toString(),
                "--db-url", dbUrl
        });

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(output));

        JsonNode root = OBJECT_MAPPER.readTree(output.toFile());
        assertEquals("illustrations", root.get("features").get(0).asText());
        assertEquals(1, root.get("books").size());
        assertEquals(1, root.get("books").get(0).get("illustrations").size());
        assertEquals("books/gutenberg/1342/illustrations/chapters/0.png",
                root.get("books").get(0).get("illustrations").get(0).get("imageFilename").asText());
    }

    @Test
    void exportApplyAllCached_writesPortraitBundleJson() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_export_portraits;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedExportData(dbUrl);

        Path output = tempDir.resolve("portraits-export.json");
        RunResult result = runCli(new String[]{
                "export",
                "--feature", "portraits",
                "--all-cached",
                "--apply",
                "--output", output.toString(),
                "--db-url", dbUrl
        });

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(output));

        JsonNode root = OBJECT_MAPPER.readTree(output.toFile());
        assertEquals("portraits", root.get("features").get(0).asText());
        assertEquals(1, root.get("books").size());
        assertEquals(1, root.get("books").get(0).get("portraits").size());
        assertEquals("Elizabeth Bennet", root.get("books").get(0).get("portraits").get(0).get("name").asText());
    }

    @Test
    void exportApplyAllCached_writesCoverBundleJson() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_export_covers;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedExportData(dbUrl);

        Path output = tempDir.resolve("covers-export.json");
        RunResult result = runCli(new String[]{
                "export",
                "--feature", "covers",
                "--all-cached",
                "--apply",
                "--output", output.toString(),
                "--db-url", dbUrl
        });

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(output));

        JsonNode root = OBJECT_MAPPER.readTree(output.toFile());
        assertEquals("covers", root.get("features").get(0).asText());
        assertEquals(1, root.get("books").size());
        assertEquals(1, root.get("books").get(0).get("covers").size());
        assertEquals("books/gutenberg/1342/covers/cover.png",
                root.get("books").get(0).get("covers").get(0).get("imageFilename").asText());
    }

    @Test
    void exportApplyBookSourceIds_supportsMultiBookExport() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_export_multi;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedExportData(dbUrl);
        seedSecondCompletedRecap(dbUrl);

        Path output = tempDir.resolve("recaps-multi-export.json");
        RunResult result = runCli(new String[]{
                "export",
                "--feature", "recaps",
                "--book-source-id", "1342,84",
                "--apply",
                "--output", output.toString(),
                "--db-url", dbUrl
        });

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(output));

        JsonNode root = OBJECT_MAPPER.readTree(output.toFile());
        assertEquals(2, root.get("books").size());
    }

    @Test
    void importApply_skipAndOverwriteConflictPoliciesBehaveAsExpected() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_import;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedImportData(dbUrl);

        Path input = tempDir.resolve("import.json");
        Files.writeString(input, """
                {
                  "formatVersion": "1.0",
                  "exportedAt": "2026-02-11T21:30:00Z",
                  "features": ["recaps"],
                  "books": [
                    {
                      "source": "gutenberg",
                      "sourceId": "1342",
                      "title": "Pride and Prejudice",
                      "author": "Jane Austen",
                      "recaps": [
                        {
                          "chapterIndex": 0,
                          "status": "COMPLETED",
                          "promptVersion": "v2",
                          "modelName": "qwen2.5:32b",
                          "generatedAt": "2026-02-11T18:09:25Z",
                          "updatedAt": "2026-02-11T18:09:25Z",
                          "payloadJson": "{\\"shortSummary\\":\\"new-summary\\"}"
                        }
                      ]
                    }
                  ]
                }
                """);

        RunResult skipResult = runCli(new String[]{
                "import",
                "--feature", "recaps",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "skip",
                "--db-url", dbUrl
        });
        assertEquals(0, skipResult.exitCode());
        assertEquals("{\"shortSummary\":\"existing-summary\"}", readPayload(dbUrl, "recap-1"));

        RunResult overwriteResult = runCli(new String[]{
                "import",
                "--feature", "recaps",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "overwrite",
                "--db-url", dbUrl
        });
        assertEquals(0, overwriteResult.exitCode());
        assertEquals("{\"shortSummary\":\"new-summary\"}", readPayload(dbUrl, "recap-1"));
    }

    @Test
    void importApplyQuizzes_skipAndOverwriteConflictPoliciesBehaveAsExpected() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_import_quizzes;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedImportData(dbUrl);

        Path input = tempDir.resolve("import-quizzes.json");
        Files.writeString(input, """
                {
                  "formatVersion": "1.0",
                  "exportedAt": "2026-02-11T21:30:00Z",
                  "features": ["quizzes"],
                  "books": [
                    {
                      "source": "gutenberg",
                      "sourceId": "1342",
                      "title": "Pride and Prejudice",
                      "author": "Jane Austen",
                      "quizzes": [
                        {
                          "chapterIndex": 0,
                          "status": "COMPLETED",
                          "promptVersion": "v2",
                          "modelName": "qwen2.5:32b",
                          "generatedAt": "2026-02-11T18:09:25Z",
                          "updatedAt": "2026-02-11T18:09:25Z",
                          "payloadJson": "{\\"questions\\":[{\\"question\\":\\"Updated?\\"}]}"
                        }
                      ]
                    }
                  ]
                }
                """);

        RunResult skipResult = runCli(new String[]{
                "import",
                "--feature", "quizzes",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "skip",
                "--db-url", dbUrl
        });
        assertEquals(0, skipResult.exitCode());
        assertEquals("{\"questions\":[{\"question\":\"Existing?\"}]}", readQuizPayload(dbUrl, "quiz-1"));

        RunResult overwriteResult = runCli(new String[]{
                "import",
                "--feature", "quizzes",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "overwrite",
                "--db-url", dbUrl
        });
        assertEquals(0, overwriteResult.exitCode());
        assertEquals("{\"questions\":[{\"question\":\"Updated?\"}]}", readQuizPayload(dbUrl, "quiz-1"));
    }

    @Test
    void importApplyIllustrations_skipAndOverwriteConflictPoliciesBehaveAsExpected() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_import_illustrations;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedImportData(dbUrl);

        Path input = tempDir.resolve("import-illustrations.json");
        Files.writeString(input, """
                {
                  "formatVersion": "1.0",
                  "exportedAt": "2026-02-11T21:30:00Z",
                  "features": ["illustrations"],
                  "books": [
                    {
                      "source": "gutenberg",
                      "sourceId": "1342",
                      "title": "Pride and Prejudice",
                      "author": "Jane Austen",
                      "illustrations": [
                        {
                          "chapterIndex": 0,
                          "status": "COMPLETED",
                          "imageFilename": "books/gutenberg/1342/illustrations/chapters/0-new.png",
                          "generatedPrompt": "new prompt",
                          "completedAt": "2026-02-11T18:09:25Z"
                        }
                      ]
                    }
                  ]
                }
                """);

        RunResult skipResult = runCli(new String[]{
                "import",
                "--feature", "illustrations",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "skip",
                "--db-url", dbUrl
        });
        assertEquals(0, skipResult.exitCode());
        assertEquals("books/gutenberg/1342/illustrations/chapters/0-old.png", readIllustrationFilename(dbUrl, "ill-1"));

        RunResult overwriteResult = runCli(new String[]{
                "import",
                "--feature", "illustrations",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "overwrite",
                "--db-url", dbUrl
        });
        assertEquals(0, overwriteResult.exitCode());
        assertEquals("books/gutenberg/1342/illustrations/chapters/0-new.png", readIllustrationFilename(dbUrl, "ill-1"));
    }

    @Test
    void importApplyPortraits_skipAndOverwriteConflictPoliciesBehaveAsExpected() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_import_portraits;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedImportData(dbUrl);

        Path input = tempDir.resolve("import-portraits.json");
        Files.writeString(input, """
                {
                  "formatVersion": "1.0",
                  "exportedAt": "2026-02-11T21:30:00Z",
                  "features": ["portraits"],
                  "books": [
                    {
                      "source": "gutenberg",
                      "sourceId": "1342",
                      "title": "Pride and Prejudice",
                      "author": "Jane Austen",
                      "portraits": [
                        {
                          "name": "Elizabeth Bennet",
                          "description": "Updated description",
                          "characterType": "PRIMARY",
                          "firstChapterIndex": 0,
                          "firstParagraphIndex": 3,
                          "status": "COMPLETED",
                          "portraitFilename": "books/gutenberg/1342/portraits/characters/elizabeth-bennet-new.png",
                          "portraitPrompt": "new portrait prompt",
                          "createdAt": "2026-02-11T18:00:00Z",
                          "completedAt": "2026-02-11T18:09:25Z"
                        }
                      ]
                    }
                  ]
                }
                """);

        RunResult skipResult = runCli(new String[]{
                "import",
                "--feature", "portraits",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "skip",
                "--db-url", dbUrl
        });
        assertEquals(0, skipResult.exitCode());
        assertEquals("books/gutenberg/1342/portraits/characters/elizabeth-bennet-old.png", readPortraitFilename(dbUrl, "char-1"));

        RunResult overwriteResult = runCli(new String[]{
                "import",
                "--feature", "portraits",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "overwrite",
                "--db-url", dbUrl
        });
        assertEquals(0, overwriteResult.exitCode());
        assertEquals("books/gutenberg/1342/portraits/characters/elizabeth-bennet-new.png", readPortraitFilename(dbUrl, "char-1"));
    }

    @Test
    void importApplyCovers_skipAndOverwriteConflictPoliciesBehaveAsExpected() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_import_covers;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedImportData(dbUrl);

        Path input = tempDir.resolve("import-covers.json");
        Files.writeString(input, """
                {
                  "formatVersion": "1.0",
                  "exportedAt": "2026-02-11T21:30:00Z",
                  "features": ["covers"],
                  "books": [
                    {
                      "source": "gutenberg",
                      "sourceId": "1342",
                      "title": "Pride and Prejudice",
                      "author": "Jane Austen",
                      "covers": [
                        {
                          "status": "COMPLETED",
                          "imageFilename": "books/gutenberg/1342/covers/cover-new.png",
                          "generatedPrompt": "new cover prompt",
                          "promptOverride": "manual prompt",
                          "coverSource": "prompt_override",
                          "createdAt": "2026-02-11T18:00:00Z",
                          "completedAt": "2026-02-11T18:09:25Z"
                        }
                      ]
                    }
                  ]
                }
                """);

        RunResult skipResult = runCli(new String[]{
                "import",
                "--feature", "covers",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "skip",
                "--db-url", dbUrl
        });
        assertEquals(0, skipResult.exitCode());
        assertEquals("books/gutenberg/1342/covers/cover-old.png", readCoverFilename(dbUrl, "cover-1"));

        RunResult overwriteResult = runCli(new String[]{
                "import",
                "--feature", "covers",
                "--input", input.toString(),
                "--apply",
                "--on-conflict", "overwrite",
                "--db-url", dbUrl
        });
        assertEquals(0, overwriteResult.exitCode());
        assertEquals("books/gutenberg/1342/covers/cover-new.png", readCoverFilename(dbUrl, "cover-1"));
    }

    @Test
    void importDryRun_doesNotMutateDatabase() throws Exception {
        String dbUrl = "jdbc:h2:mem:cache_transfer_import_dry_run;DB_CLOSE_DELAY=-1";
        createSchema(dbUrl);
        seedImportData(dbUrl);

        Path input = tempDir.resolve("import-dry-run.json");
        Files.writeString(input, """
                {
                  "formatVersion": "1.0",
                  "exportedAt": "2026-02-11T21:30:00Z",
                  "features": ["recaps"],
                  "books": [
                    {
                      "source": "gutenberg",
                      "sourceId": "1342",
                      "title": "Pride and Prejudice",
                      "author": "Jane Austen",
                      "recaps": [
                        {
                          "chapterIndex": 0,
                          "status": "COMPLETED",
                          "promptVersion": "v2",
                          "modelName": "qwen2.5:32b",
                          "generatedAt": "2026-02-11T18:09:25Z",
                          "updatedAt": "2026-02-11T18:09:25Z",
                          "payloadJson": "{\\"shortSummary\\":\\"dry-run-updated\\"}"
                        }
                      ]
                    }
                  ]
                }
                """);

        RunResult dryRunResult = runCli(new String[]{
                "import",
                "--feature", "recaps",
                "--input", input.toString(),
                "--on-conflict", "overwrite",
                "--db-url", dbUrl
        });

        assertEquals(0, dryRunResult.exitCode());
        assertTrue(dryRunResult.stdout().contains("Dry-run"));
        assertEquals("{\"shortSummary\":\"existing-summary\"}", readPayload(dbUrl, "recap-1"));
    }

    @Test
    void normalizeDbUrl_addsDbCloseOnExitFalseForH2WhenMissing() {
        String normalized = CacheTransferRunner.normalizeDbUrl("jdbc:h2:file:./data/library;DB_CLOSE_DELAY=-1");
        assertTrue(normalized.contains("DB_CLOSE_ON_EXIT=FALSE"));
    }

    @Test
    void normalizeDbUrl_doesNotChangeNonH2OrExistingSetting() {
        assertEquals(
                "jdbc:postgresql://localhost:5432/app",
                CacheTransferRunner.normalizeDbUrl("jdbc:postgresql://localhost:5432/app")
        );
        assertEquals(
                "jdbc:h2:file:./data/library;DB_CLOSE_ON_EXIT=FALSE",
                CacheTransferRunner.normalizeDbUrl("jdbc:h2:file:./data/library;DB_CLOSE_ON_EXIT=FALSE")
        );
    }

    private static RunResult runCli(String[] args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(outBuffer);
             PrintStream err = new PrintStream(errBuffer)) {
            int exitCode = CacheTransferRunner.run(args, out, err);
            return new RunResult(exitCode, outBuffer.toString(), errBuffer.toString());
        }
    }

    private static void createSchema(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table books (
                        id varchar(64) primary key,
                        source varchar(255),
                        source_id varchar(255),
                        title varchar(255),
                        author varchar(255)
                    )
                    """);
            statement.execute("""
                    create table chapters (
                        id varchar(64) primary key,
                        book_id varchar(64) not null,
                        chapter_index int not null,
                        title varchar(255)
                    )
                    """);
            statement.execute("""
                    create table chapter_recaps (
                        id varchar(64) primary key,
                        chapter_id varchar(64) not null unique,
                        status varchar(32) not null,
                        updated_at timestamp not null,
                        generated_at timestamp,
                        prompt_version varchar(100),
                        model_name varchar(200),
                        payload_json clob
                    )
                    """);
            statement.execute("""
                    create table chapter_quizzes (
                        id varchar(64) primary key,
                        chapter_id varchar(64) not null unique,
                        status varchar(32) not null,
                        updated_at timestamp not null,
                        generated_at timestamp,
                        prompt_version varchar(100),
                        model_name varchar(200),
                        payload_json clob
                    )
                    """);
            statement.execute("""
                    create table illustrations (
                        id varchar(64) primary key,
                        chapter_id varchar(64) not null unique,
                        status varchar(32) not null,
                        image_filename varchar(255),
                        generated_prompt varchar(2000),
                        created_at timestamp not null,
                        completed_at timestamp,
                        error_message varchar(1000),
                        retry_count int not null default 0,
                        next_retry_at timestamp
                    )
                    """);
            statement.execute("""
                    create table characters (
                        id varchar(64) primary key,
                        book_id varchar(64) not null,
                        name varchar(255) not null,
                        description varchar(2000),
                        first_chapter_id varchar(64) not null,
                        first_paragraph_index int not null,
                        portrait_filename varchar(255),
                        portrait_prompt varchar(2000),
                        status varchar(32) not null,
                        character_type varchar(32) not null,
                        created_at timestamp not null,
                        completed_at timestamp,
                        error_message varchar(1000),
                        retry_count int not null default 0,
                        next_retry_at timestamp,
                        constraint uk_characters_book_name unique (book_id, name)
                    )
                    """);
            statement.execute("""
                    create table book_covers (
                        id varchar(64) primary key,
                        book_id varchar(64) not null unique,
                        completed_at timestamp,
                        created_at timestamp not null,
                        error_message varchar(1000),
                        generated_prompt varchar(2000),
                        image_filename varchar(255),
                        lease_expires_at timestamp,
                        lease_owner varchar(120),
                        next_retry_at timestamp,
                        retry_count int not null default 0,
                        status varchar(64) not null,
                        prompt_override varchar(2000),
                        cover_source varchar(64)
                    )
                    """);
        }
    }

    private static void seedExportData(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into books (id, source, source_id, title, author)
                    values ('book-1', 'gutenberg', '1342', 'Pride and Prejudice', 'Jane Austen')
                    """);
            statement.execute("""
                    insert into chapters (id, book_id, chapter_index, title)
                    values ('chapter-1', 'book-1', 0, 'Chapter 1')
                    """);
            statement.execute("""
                    insert into chapter_recaps (id, chapter_id, status, updated_at, generated_at, prompt_version, model_name, payload_json)
                    values ('recap-1', 'chapter-1', 'COMPLETED', TIMESTAMP '2026-02-11 18:09:25', TIMESTAMP '2026-02-11 18:09:25', 'v1', 'qwen2.5:32b', '{"shortSummary":"existing-summary"}')
                    """);
            statement.execute("""
                    insert into books (id, source, source_id, title, author)
                    values ('book-2', 'gutenberg', '84', 'Frankenstein', 'Mary Shelley')
                    """);
            statement.execute("""
                    insert into chapters (id, book_id, chapter_index, title)
                    values ('chapter-2', 'book-2', 0, 'Chapter 1')
                    """);
            statement.execute("""
                    insert into chapter_recaps (id, chapter_id, status, updated_at, generated_at, prompt_version, model_name, payload_json)
                    values ('recap-2', 'chapter-2', 'FAILED', TIMESTAMP '2026-02-11 18:09:25', null, 'v1', 'qwen2.5:32b', '{"shortSummary":"failed"}')
                    """);
            statement.execute("""
                    insert into chapter_quizzes (id, chapter_id, status, updated_at, generated_at, prompt_version, model_name, payload_json)
                    values ('quiz-1', 'chapter-1', 'COMPLETED', TIMESTAMP '2026-02-11 18:09:25', TIMESTAMP '2026-02-11 18:09:25', 'v1', 'qwen2.5:32b', '{"questions":[{"question":"Existing?"}]}')
                    """);
            statement.execute("""
                    insert into chapter_quizzes (id, chapter_id, status, updated_at, generated_at, prompt_version, model_name, payload_json)
                    values ('quiz-2', 'chapter-2', 'FAILED', TIMESTAMP '2026-02-11 18:09:25', null, 'v1', 'qwen2.5:32b', '{"questions":[{"question":"Failed?"}]}')
                    """);
            statement.execute("""
                    insert into illustrations (id, chapter_id, status, image_filename, generated_prompt, created_at, completed_at, retry_count)
                    values ('ill-1', 'chapter-1', 'COMPLETED', 'books/gutenberg/1342/illustrations/chapters/0.png', 'style prompt', TIMESTAMP '2026-02-11 18:00:00', TIMESTAMP '2026-02-11 18:09:25', 0)
                    """);
            statement.execute("""
                    insert into characters (
                        id, book_id, name, description, first_chapter_id, first_paragraph_index,
                        portrait_filename, portrait_prompt, status, character_type, created_at, completed_at, retry_count
                    )
                    values (
                        'char-1', 'book-1', 'Elizabeth Bennet', 'Protagonist', 'chapter-1', 3,
                        'books/gutenberg/1342/portraits/characters/elizabeth-bennet.png', 'portrait prompt',
                        'COMPLETED', 'PRIMARY', TIMESTAMP '2026-02-11 18:00:00', TIMESTAMP '2026-02-11 18:09:25', 0
                    )
                    """);
            statement.execute("""
                    insert into book_covers (
                        id, book_id, status, image_filename, generated_prompt, created_at, completed_at, retry_count, cover_source
                    )
                    values (
                        'cover-1', 'book-1', 'COMPLETED', 'books/gutenberg/1342/covers/cover.png', 'cover prompt',
                        TIMESTAMP '2026-02-11 18:00:00', TIMESTAMP '2026-02-11 18:09:25', 0, 'generated'
                    )
                    """);
        }
    }

    private static void seedSecondCompletedRecap(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    update chapter_recaps
                    set status = 'COMPLETED',
                        generated_at = TIMESTAMP '2026-02-11 18:09:25',
                        payload_json = '{"shortSummary":"second-completed"}'
                    where id = 'recap-2'
                    """);
        }
    }

    private static void seedImportData(String dbUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into books (id, source, source_id, title, author)
                    values ('book-1', 'gutenberg', '1342', 'Pride and Prejudice', 'Jane Austen')
                    """);
            statement.execute("""
                    insert into chapters (id, book_id, chapter_index, title)
                    values ('chapter-1', 'book-1', 0, 'Chapter 1')
                    """);
            statement.execute("""
                    insert into chapter_recaps (id, chapter_id, status, updated_at, generated_at, prompt_version, model_name, payload_json)
                    values ('recap-1', 'chapter-1', 'COMPLETED', TIMESTAMP '2026-02-11 18:09:25', TIMESTAMP '2026-02-11 18:09:25', 'v1', 'qwen2.5:32b', '{"shortSummary":"existing-summary"}')
                    """);
            statement.execute("""
                    insert into chapter_quizzes (id, chapter_id, status, updated_at, generated_at, prompt_version, model_name, payload_json)
                    values ('quiz-1', 'chapter-1', 'COMPLETED', TIMESTAMP '2026-02-11 18:09:25', TIMESTAMP '2026-02-11 18:09:25', 'v1', 'qwen2.5:32b', '{"questions":[{"question":"Existing?"}]}')
                    """);
            statement.execute("""
                    insert into illustrations (id, chapter_id, status, image_filename, generated_prompt, created_at, completed_at, retry_count)
                    values ('ill-1', 'chapter-1', 'COMPLETED', 'books/gutenberg/1342/illustrations/chapters/0-old.png', 'old prompt', TIMESTAMP '2026-02-11 18:00:00', TIMESTAMP '2026-02-11 18:09:25', 0)
                    """);
            statement.execute("""
                    insert into characters (
                        id, book_id, name, description, first_chapter_id, first_paragraph_index,
                        portrait_filename, portrait_prompt, status, character_type, created_at, completed_at, retry_count
                    )
                    values (
                        'char-1', 'book-1', 'Elizabeth Bennet', 'Old description', 'chapter-1', 1,
                        'books/gutenberg/1342/portraits/characters/elizabeth-bennet-old.png', 'old portrait prompt',
                        'COMPLETED', 'SECONDARY', TIMESTAMP '2026-02-11 18:00:00', TIMESTAMP '2026-02-11 18:09:25', 0
                    )
                    """);
            statement.execute("""
                    insert into book_covers (
                        id, book_id, status, image_filename, generated_prompt, created_at, completed_at, retry_count, cover_source
                    )
                    values (
                        'cover-1', 'book-1', 'COMPLETED', 'books/gutenberg/1342/covers/cover-old.png', 'old cover prompt',
                        TIMESTAMP '2026-02-11 18:00:00', TIMESTAMP '2026-02-11 18:09:25', 0, 'generated'
                    )
                    """);
        }
    }

    private static String readPayload(String dbUrl, String recapId) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select payload_json from chapter_recaps where id = '" + recapId + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new IllegalStateException("Recap not found: " + recapId);
        }
    }

    private static String readQuizPayload(String dbUrl, String quizId) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select payload_json from chapter_quizzes where id = '" + quizId + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new IllegalStateException("Quiz not found: " + quizId);
        }
    }

    private static String readIllustrationFilename(String dbUrl, String illustrationId) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select image_filename from illustrations where id = '" + illustrationId + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new IllegalStateException("Illustration not found: " + illustrationId);
        }
    }

    private static String readPortraitFilename(String dbUrl, String characterId) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select portrait_filename from characters where id = '" + characterId + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new IllegalStateException("Character not found: " + characterId);
        }
    }

    private static String readCoverFilename(String dbUrl, String coverId) throws Exception {
        try (Connection connection = DriverManager.getConnection(dbUrl, "sa", "");
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select image_filename from book_covers where id = '" + coverId + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new IllegalStateException("Cover not found: " + coverId);
        }
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
    }
}
