package com.classicchatreader.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

public class CacheTransferRunner {

    private static final String COMMAND_EXPORT = "export";
    private static final String COMMAND_IMPORT = "import";

    private static final String APPLY_FLAG = "--apply";
    private static final String HELP_FLAG = "--help";
    private static final String FEATURE_FLAG = "--feature";
    private static final String BOOK_SOURCE_ID_FLAG = "--book-source-id";
    private static final String ALL_CACHED_FLAG = "--all-cached";
    private static final String INPUT_FLAG = "--input";
    private static final String OUTPUT_FLAG = "--output";
    private static final String ON_CONFLICT_FLAG = "--on-conflict";

    private static final String DB_URL_FLAG = "--db-url";
    private static final String DB_USER_FLAG = "--db-user";
    private static final String DB_PASSWORD_FLAG = "--db-password";

    private static final String FEATURE_RECAPS = "recaps";
    private static final String FEATURE_QUIZZES = "quizzes";
    private static final String FEATURE_ILLUSTRATIONS = "illustrations";
    private static final String FEATURE_PORTRAITS = "portraits";
    private static final String FORMAT_VERSION = "1.0";

    private static final Set<String> GLOBAL_OPTIONS = Set.of(
            APPLY_FLAG, HELP_FLAG, FEATURE_FLAG, BOOK_SOURCE_ID_FLAG, ALL_CACHED_FLAG,
            INPUT_FLAG, OUTPUT_FLAG, ON_CONFLICT_FLAG, DB_URL_FLAG, DB_USER_FLAG, DB_PASSWORD_FLAG
    );

    private static final Set<String> EXPORT_OPTIONS = Set.of(
            APPLY_FLAG, HELP_FLAG, FEATURE_FLAG, BOOK_SOURCE_ID_FLAG, ALL_CACHED_FLAG,
            OUTPUT_FLAG, DB_URL_FLAG, DB_USER_FLAG, DB_PASSWORD_FLAG
    );

    private static final Set<String> IMPORT_OPTIONS = Set.of(
            APPLY_FLAG, HELP_FLAG, FEATURE_FLAG, INPUT_FLAG, ON_CONFLICT_FLAG,
            DB_URL_FLAG, DB_USER_FLAG, DB_PASSWORD_FLAG
    );

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
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

        if (parsed.flags().contains(HELP_FLAG) || parsed.command() == null) {
            printUsage(out);
            return 0;
        }

        if (!COMMAND_EXPORT.equals(parsed.command()) && !COMMAND_IMPORT.equals(parsed.command())) {
            err.println("Unknown command: " + parsed.command());
            printUsage(err);
            return 1;
        }

        Set<String> allowedOptions = COMMAND_EXPORT.equals(parsed.command()) ? EXPORT_OPTIONS : IMPORT_OPTIONS;
        for (String option : parsed.allOptions()) {
            if (!GLOBAL_OPTIONS.contains(option) || !allowedOptions.contains(option)) {
                err.println("Unsupported option for '" + parsed.command() + "': " + option);
                return 1;
            }
        }

        String featureRaw = parsed.optionValue(FEATURE_FLAG).orElse(FEATURE_RECAPS).trim().toLowerCase();
        Optional<TransferFeature> feature = TransferFeature.fromValue(featureRaw);
        if (feature.isEmpty()) {
            err.println("Unsupported feature: " + featureRaw + " (supported: recaps, quizzes, illustrations, portraits).");
            return 1;
        }

        DbConfig dbConfig = resolveDbConfig(parsed);

        try (Connection connection = DriverManager.getConnection(dbConfig.url(), dbConfig.user(), dbConfig.password())) {
            if (COMMAND_EXPORT.equals(parsed.command())) {
                ExportOptions options = buildExportOptions(parsed);
                Summary summary = switch (feature.get()) {
                    case RECAPS -> exportRecaps(connection, options, out, err);
                    case QUIZZES -> exportQuizzes(connection, options, out, err);
                    case ILLUSTRATIONS -> exportIllustrations(connection, options, out, err);
                    case PORTRAITS -> exportPortraits(connection, options, out, err);
                };
                printSummary("export", feature.get(), summary, options.apply(), out);
                return summary.validationErrors() > 0 ? 1 : 0;
            }

            ImportOptions options = buildImportOptions(parsed);
            if (options.apply()) {
                connection.setAutoCommit(false);
            }

            Summary summary = switch (feature.get()) {
                case RECAPS -> importRecaps(connection, options, out, err);
                case QUIZZES -> importQuizzes(connection, options, out, err);
                case ILLUSTRATIONS -> importIllustrations(connection, options, out, err);
                case PORTRAITS -> importPortraits(connection, options, out, err);
            };

            if (options.apply()) {
                if (summary.validationErrors() > 0) {
                    connection.rollback();
                } else {
                    connection.commit();
                }
            }

            printSummary("import", feature.get(), summary, options.apply(), out);
            return summary.validationErrors() > 0 ? 1 : 0;
        } catch (Exception e) {
            err.println("Cache transfer failed: " + e.getMessage());
            return 1;
        }
    }

    private static DbConfig resolveDbConfig(ParsedArgs parsed) {
        Properties properties = loadProperties();
        String dbUrl = parsed.optionValue(DB_URL_FLAG)
                .orElseGet(() -> firstNonBlank(
                        System.getenv("SPRING_DATASOURCE_URL"),
                        System.getenv("DATABASE_URL"),
                        properties.getProperty("spring.datasource.url"),
                        "jdbc:h2:file:./data/library"));
        String dbUser = parsed.optionValue(DB_USER_FLAG)
                .orElseGet(() -> firstNonBlank(
                        System.getenv("SPRING_DATASOURCE_USERNAME"),
                        System.getenv("DATABASE_USERNAME"),
                        properties.getProperty("spring.datasource.username"),
                        "sa"));
        String dbPassword = parsed.optionValue(DB_PASSWORD_FLAG)
                .orElseGet(() -> firstNonBlank(
                        System.getenv("SPRING_DATASOURCE_PASSWORD"),
                        System.getenv("DATABASE_PASSWORD"),
                        properties.getProperty("spring.datasource.password"),
                        ""));
        return new DbConfig(normalizeDbUrl(dbUrl), dbUser, dbPassword);
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

    private static ExportOptions buildExportOptions(ParsedArgs parsed) {
        boolean apply = parsed.flags().contains(APPLY_FLAG);
        boolean allCached = parsed.flags().contains(ALL_CACHED_FLAG);
        Set<String> sourceIds = parseCsvSet(parsed.optionValue(BOOK_SOURCE_ID_FLAG).orElse(null));
        Path outputPath = parsed.optionValue(OUTPUT_FLAG).map(Path::of).orElse(null);

        if (allCached && !sourceIds.isEmpty()) {
            throw new IllegalArgumentException("--all-cached and --book-source-id are mutually exclusive.");
        }
        if (!allCached && sourceIds.isEmpty()) {
            throw new IllegalArgumentException("Export requires --all-cached or --book-source-id.");
        }
        if (apply && outputPath == null) {
            throw new IllegalArgumentException("Export with --apply requires --output <path>.");
        }

        return new ExportOptions(apply, allCached, sourceIds, outputPath);
    }

    private static ImportOptions buildImportOptions(ParsedArgs parsed) {
        boolean apply = parsed.flags().contains(APPLY_FLAG);
        Path inputPath = parsed.optionValue(INPUT_FLAG)
                .map(Path::of)
                .orElseThrow(() -> new IllegalArgumentException("Import requires --input <path>."));
        String conflictRaw = parsed.optionValue(ON_CONFLICT_FLAG).orElse("skip").trim().toLowerCase();
        OnConflict onConflict = OnConflict.fromValue(conflictRaw)
                .orElseThrow(() -> new IllegalArgumentException("Invalid --on-conflict value: " + conflictRaw));
        return new ImportOptions(apply, inputPath, onConflict);
    }

    static Summary exportRecaps(Connection connection, ExportOptions options, PrintStream out, PrintStream err)
            throws SQLException, IOException {
        Summary summary = new Summary();

        Map<String, List<BookRow>> booksByRequestedSourceId = options.sourceIds().isEmpty()
                ? Map.of()
                : findBooksBySourceId(connection, options.sourceIds());
        if (!options.sourceIds().isEmpty()) {
            summary.booksScanned = options.sourceIds().size();
            for (String requestedSourceId : options.sourceIds()) {
                if (booksByRequestedSourceId.getOrDefault(requestedSourceId, List.of()).isEmpty()) {
                    summary.booksMissing++;
                    out.println("Book missing for sourceId=" + requestedSourceId);
                }
            }
            int matched = booksByRequestedSourceId.values().stream().mapToInt(List::size).sum();
            summary.booksMatched = matched;
        }

        List<RecapExportRow> recapRows = queryCompletedRecapRows(connection);
        Set<String> scannedBooksAllCached = new HashSet<>();
        Set<String> missingBookIds = new HashSet<>();
        Set<String> allowedBookIds = new HashSet<>();
        if (!options.sourceIds().isEmpty()) {
            for (List<BookRow> rows : booksByRequestedSourceId.values()) {
                for (BookRow row : rows) {
                    allowedBookIds.add(row.id());
                }
            }
        }

        Map<String, TransferBookBuilder> builders = new LinkedHashMap<>();

        for (RecapExportRow row : recapRows) {
            if (!options.sourceIds().isEmpty() && !allowedBookIds.contains(row.bookId())) {
                continue;
            }

            if (options.allCached()) {
                scannedBooksAllCached.add(row.bookId());
            }

            if (isBlank(row.source()) || isBlank(row.sourceId())) {
                if (missingBookIds.add(row.bookId())) {
                    summary.booksMissing++;
                    out.println("Skipping book with missing source/sourceId: bookId=" + row.bookId());
                }
                continue;
            }

            String key = row.source() + "\u0000" + row.sourceId();
            TransferBookBuilder builder = builders.computeIfAbsent(
                    key,
                    ignored -> new TransferBookBuilder(row.source(), row.sourceId(), row.title(), row.author())
            );
            builder.recaps().add(new TransferRecap(
                    row.chapterIndex(),
                    ChapterStatus.COMPLETED.value(),
                    row.promptVersion(),
                    row.modelName(),
                    formatDateTime(row.generatedAt()),
                    formatDateTime(row.updatedAt()),
                    row.payloadJson()
            ));
            summary.recapsExported++;
        }

        if (options.allCached()) {
            summary.booksScanned = scannedBooksAllCached.size();
            summary.booksMatched = scannedBooksAllCached.size() - missingBookIds.size();
        }

        List<TransferBook> books = builders.values().stream()
                .map(TransferBookBuilder::build)
                .sorted(Comparator.comparing(TransferBook::source).thenComparing(TransferBook::sourceId))
                .toList();

        for (TransferBook book : books) {
            if (book.recaps() != null) {
                book.recaps().sort(Comparator.comparingInt(TransferRecap::chapterIndex));
            }
        }

        TransferBundle bundle = new TransferBundle(
                FORMAT_VERSION,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                List.of(FEATURE_RECAPS),
                books
        );

        if (!options.apply()) {
            out.println("Dry-run: export file not written (use --apply to write JSON).");
            return summary;
        }

        Path outputPath = options.outputPath();
        if (outputPath == null) {
            summary.validationErrors++;
            err.println("Missing output path.");
            return summary;
        }
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), bundle);
        out.println("Wrote recap transfer JSON: " + outputPath.toAbsolutePath());
        return summary;
    }

    static Summary exportQuizzes(Connection connection, ExportOptions options, PrintStream out, PrintStream err)
            throws SQLException, IOException {
        Summary summary = new Summary();

        Map<String, List<BookRow>> booksByRequestedSourceId = options.sourceIds().isEmpty()
                ? Map.of()
                : findBooksBySourceId(connection, options.sourceIds());
        if (!options.sourceIds().isEmpty()) {
            summary.booksScanned = options.sourceIds().size();
            for (String requestedSourceId : options.sourceIds()) {
                if (booksByRequestedSourceId.getOrDefault(requestedSourceId, List.of()).isEmpty()) {
                    summary.booksMissing++;
                    out.println("Book missing for sourceId=" + requestedSourceId);
                }
            }
            int matched = booksByRequestedSourceId.values().stream().mapToInt(List::size).sum();
            summary.booksMatched = matched;
        }

        List<QuizExportRow> quizRows = queryCompletedQuizRows(connection);
        Set<String> scannedBooksAllCached = new HashSet<>();
        Set<String> missingBookIds = new HashSet<>();
        Set<String> allowedBookIds = new HashSet<>();
        if (!options.sourceIds().isEmpty()) {
            for (List<BookRow> rows : booksByRequestedSourceId.values()) {
                for (BookRow row : rows) {
                    allowedBookIds.add(row.id());
                }
            }
        }

        Map<String, TransferBookBuilder> builders = new LinkedHashMap<>();

        for (QuizExportRow row : quizRows) {
            if (!options.sourceIds().isEmpty() && !allowedBookIds.contains(row.bookId())) {
                continue;
            }

            if (options.allCached()) {
                scannedBooksAllCached.add(row.bookId());
            }

            if (isBlank(row.source()) || isBlank(row.sourceId())) {
                if (missingBookIds.add(row.bookId())) {
                    summary.booksMissing++;
                    out.println("Skipping book with missing source/sourceId: bookId=" + row.bookId());
                }
                continue;
            }

            String key = row.source() + "\u0000" + row.sourceId();
            TransferBookBuilder builder = builders.computeIfAbsent(
                    key,
                    ignored -> new TransferBookBuilder(row.source(), row.sourceId(), row.title(), row.author())
            );
            builder.quizzes().add(new TransferQuiz(
                    row.chapterIndex(),
                    ChapterStatus.COMPLETED.value(),
                    row.promptVersion(),
                    row.modelName(),
                    formatDateTime(row.generatedAt()),
                    formatDateTime(row.updatedAt()),
                    row.payloadJson()
            ));
            summary.quizzesExported++;
        }

        if (options.allCached()) {
            summary.booksScanned = scannedBooksAllCached.size();
            summary.booksMatched = scannedBooksAllCached.size() - missingBookIds.size();
        }

        List<TransferBook> books = builders.values().stream()
                .map(TransferBookBuilder::build)
                .sorted(Comparator.comparing(TransferBook::source).thenComparing(TransferBook::sourceId))
                .toList();

        for (TransferBook book : books) {
            if (book.quizzes() != null) {
                book.quizzes().sort(Comparator.comparingInt(TransferQuiz::chapterIndex));
            }
        }

        TransferBundle bundle = new TransferBundle(
                FORMAT_VERSION,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                List.of(FEATURE_QUIZZES),
                books
        );

        if (!options.apply()) {
            out.println("Dry-run: export file not written (use --apply to write JSON).");
            return summary;
        }

        Path outputPath = options.outputPath();
        if (outputPath == null) {
            summary.validationErrors++;
            err.println("Missing output path.");
            return summary;
        }
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), bundle);
        out.println("Wrote quiz transfer JSON: " + outputPath.toAbsolutePath());
        return summary;
    }

    static Summary exportIllustrations(Connection connection, ExportOptions options, PrintStream out, PrintStream err)
            throws SQLException, IOException {
        Summary summary = new Summary();

        Map<String, List<BookRow>> booksByRequestedSourceId = options.sourceIds().isEmpty()
                ? Map.of()
                : findBooksBySourceId(connection, options.sourceIds());
        if (!options.sourceIds().isEmpty()) {
            summary.booksScanned = options.sourceIds().size();
            for (String requestedSourceId : options.sourceIds()) {
                if (booksByRequestedSourceId.getOrDefault(requestedSourceId, List.of()).isEmpty()) {
                    summary.booksMissing++;
                    out.println("Book missing for sourceId=" + requestedSourceId);
                }
            }
            int matched = booksByRequestedSourceId.values().stream().mapToInt(List::size).sum();
            summary.booksMatched = matched;
        }

        List<IllustrationExportRow> illustrationRows = queryCompletedIllustrationRows(connection);
        Set<String> scannedBooksAllCached = new HashSet<>();
        Set<String> missingBookIds = new HashSet<>();
        Set<String> allowedBookIds = new HashSet<>();
        if (!options.sourceIds().isEmpty()) {
            for (List<BookRow> rows : booksByRequestedSourceId.values()) {
                for (BookRow row : rows) {
                    allowedBookIds.add(row.id());
                }
            }
        }

        Map<String, TransferBookBuilder> builders = new LinkedHashMap<>();

        for (IllustrationExportRow row : illustrationRows) {
            if (!options.sourceIds().isEmpty() && !allowedBookIds.contains(row.bookId())) {
                continue;
            }

            if (options.allCached()) {
                scannedBooksAllCached.add(row.bookId());
            }

            if (isBlank(row.source()) || isBlank(row.sourceId())) {
                if (missingBookIds.add(row.bookId())) {
                    summary.booksMissing++;
                    out.println("Skipping book with missing source/sourceId: bookId=" + row.bookId());
                }
                continue;
            }

            String key = row.source() + "\u0000" + row.sourceId();
            TransferBookBuilder builder = builders.computeIfAbsent(
                    key,
                    ignored -> new TransferBookBuilder(row.source(), row.sourceId(), row.title(), row.author())
            );
            builder.illustrations().add(new TransferIllustration(
                    row.chapterIndex(),
                    ChapterStatus.COMPLETED.value(),
                    row.imageFilename(),
                    row.generatedPrompt(),
                    formatDateTime(row.completedAt())
            ));
            summary.illustrationsExported++;
        }

        if (options.allCached()) {
            summary.booksScanned = scannedBooksAllCached.size();
            summary.booksMatched = scannedBooksAllCached.size() - missingBookIds.size();
        }

        List<TransferBook> books = builders.values().stream()
                .map(TransferBookBuilder::build)
                .sorted(Comparator.comparing(TransferBook::source).thenComparing(TransferBook::sourceId))
                .toList();

        for (TransferBook book : books) {
            if (book.illustrations() != null) {
                book.illustrations().sort(Comparator.comparingInt(TransferIllustration::chapterIndex));
            }
        }

        TransferBundle bundle = new TransferBundle(
                FORMAT_VERSION,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                List.of(FEATURE_ILLUSTRATIONS),
                books
        );

        if (!options.apply()) {
            out.println("Dry-run: export file not written (use --apply to write JSON).");
            return summary;
        }

        Path outputPath = options.outputPath();
        if (outputPath == null) {
            summary.validationErrors++;
            err.println("Missing output path.");
            return summary;
        }
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), bundle);
        out.println("Wrote illustration transfer JSON: " + outputPath.toAbsolutePath());
        return summary;
    }

    static Summary exportPortraits(Connection connection, ExportOptions options, PrintStream out, PrintStream err)
            throws SQLException, IOException {
        Summary summary = new Summary();

        Map<String, List<BookRow>> booksByRequestedSourceId = options.sourceIds().isEmpty()
                ? Map.of()
                : findBooksBySourceId(connection, options.sourceIds());
        if (!options.sourceIds().isEmpty()) {
            summary.booksScanned = options.sourceIds().size();
            for (String requestedSourceId : options.sourceIds()) {
                if (booksByRequestedSourceId.getOrDefault(requestedSourceId, List.of()).isEmpty()) {
                    summary.booksMissing++;
                    out.println("Book missing for sourceId=" + requestedSourceId);
                }
            }
            int matched = booksByRequestedSourceId.values().stream().mapToInt(List::size).sum();
            summary.booksMatched = matched;
        }

        List<PortraitExportRow> portraitRows = queryCompletedPortraitRows(connection);
        Set<String> scannedBooksAllCached = new HashSet<>();
        Set<String> missingBookIds = new HashSet<>();
        Set<String> allowedBookIds = new HashSet<>();
        if (!options.sourceIds().isEmpty()) {
            for (List<BookRow> rows : booksByRequestedSourceId.values()) {
                for (BookRow row : rows) {
                    allowedBookIds.add(row.id());
                }
            }
        }

        Map<String, TransferBookBuilder> builders = new LinkedHashMap<>();

        for (PortraitExportRow row : portraitRows) {
            if (!options.sourceIds().isEmpty() && !allowedBookIds.contains(row.bookId())) {
                continue;
            }

            if (options.allCached()) {
                scannedBooksAllCached.add(row.bookId());
            }

            if (isBlank(row.source()) || isBlank(row.sourceId())) {
                if (missingBookIds.add(row.bookId())) {
                    summary.booksMissing++;
                    out.println("Skipping book with missing source/sourceId: bookId=" + row.bookId());
                }
                continue;
            }

            String key = row.source() + "\u0000" + row.sourceId();
            TransferBookBuilder builder = builders.computeIfAbsent(
                    key,
                    ignored -> new TransferBookBuilder(row.source(), row.sourceId(), row.title(), row.author())
            );
            builder.portraits().add(new TransferPortrait(
                    row.name(),
                    row.description(),
                    row.characterType(),
                    row.firstChapterIndex(),
                    row.firstParagraphIndex(),
                    ChapterStatus.COMPLETED.value(),
                    row.portraitFilename(),
                    row.portraitPrompt(),
                    formatDateTime(row.createdAt()),
                    formatDateTime(row.completedAt())
            ));
            summary.portraitsExported++;
        }

        if (options.allCached()) {
            summary.booksScanned = scannedBooksAllCached.size();
            summary.booksMatched = scannedBooksAllCached.size() - missingBookIds.size();
        }

        List<TransferBook> books = builders.values().stream()
                .map(TransferBookBuilder::build)
                .sorted(Comparator.comparing(TransferBook::source).thenComparing(TransferBook::sourceId))
                .toList();

        for (TransferBook book : books) {
            if (book.portraits() != null) {
                book.portraits().sort(Comparator
                        .comparingInt(TransferPortrait::firstChapterIndex)
                        .thenComparingInt(TransferPortrait::firstParagraphIndex)
                        .thenComparing(TransferPortrait::name, String.CASE_INSENSITIVE_ORDER));
            }
        }

        TransferBundle bundle = new TransferBundle(
                FORMAT_VERSION,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                List.of(FEATURE_PORTRAITS),
                books
        );

        if (!options.apply()) {
            out.println("Dry-run: export file not written (use --apply to write JSON).");
            return summary;
        }

        Path outputPath = options.outputPath();
        if (outputPath == null) {
            summary.validationErrors++;
            err.println("Missing output path.");
            return summary;
        }
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), bundle);
        out.println("Wrote portrait transfer JSON: " + outputPath.toAbsolutePath());
        return summary;
    }

    static Summary importRecaps(Connection connection, ImportOptions options, PrintStream out, PrintStream err)
            throws IOException, SQLException {
        Summary summary = new Summary();

        if (!Files.exists(options.inputPath())) {
            summary.validationErrors++;
            err.println("Input file does not exist: " + options.inputPath().toAbsolutePath());
            return summary;
        }

        TransferBundle bundle;
        try {
            bundle = OBJECT_MAPPER.readValue(options.inputPath().toFile(), TransferBundle.class);
        } catch (Exception e) {
            summary.validationErrors++;
            err.println("Invalid JSON: " + e.getMessage());
            return summary;
        }

        List<String> validationErrors = validateBundle(bundle, TransferFeature.RECAPS);
        summary.validationErrors += validationErrors.size();
        if (!validationErrors.isEmpty()) {
            for (String validationError : validationErrors) {
                err.println("Validation error: " + validationError);
            }
            return summary;
        }

        for (TransferBook book : nullSafeList(bundle.books())) {
            summary.booksScanned++;
            Optional<String> localBookId = findBookId(connection, book.source(), book.sourceId());
            if (localBookId.isEmpty()) {
                summary.booksMissing++;
                out.println("Book not found locally: " + book.source() + "/" + book.sourceId());
                continue;
            }

            summary.booksMatched++;
            Map<Integer, String> chaptersByIndex = findChapterIdsByIndex(connection, localBookId.get());

            for (TransferRecap recap : nullSafeList(book.recaps())) {
                String chapterId = chaptersByIndex.get(recap.chapterIndex());
                if (chapterId == null) {
                    summary.chaptersMissing++;
                    out.println("Chapter missing for book " + book.source() + "/" + book.sourceId()
                            + " chapterIndex=" + recap.chapterIndex());
                    continue;
                }

                Optional<String> existingRecapId = findRecapIdByChapter(connection, chapterId);
                if (existingRecapId.isPresent() && options.onConflict() == OnConflict.SKIP) {
                    summary.recapsSkippedConflicts++;
                    continue;
                }

                LocalDateTime generatedAt = parseDateTime(recap.generatedAt());
                LocalDateTime updatedAt = parseDateTime(recap.updatedAt());
                if (updatedAt == null) {
                    updatedAt = generatedAt != null ? generatedAt : LocalDateTime.now(ZoneOffset.UTC);
                }

                if (options.apply()) {
                    if (existingRecapId.isPresent()) {
                        updateRecap(connection, existingRecapId.get(), recap, generatedAt, updatedAt);
                    } else {
                        insertRecap(connection, chapterId, recap, generatedAt, updatedAt);
                    }
                }

                summary.recapsImported++;
            }
        }

        if (!options.apply()) {
            out.println("Dry-run: no database writes applied (use --apply to persist changes).");
        }
        return summary;
    }

    static Summary importQuizzes(Connection connection, ImportOptions options, PrintStream out, PrintStream err)
            throws IOException, SQLException {
        Summary summary = new Summary();

        if (!Files.exists(options.inputPath())) {
            summary.validationErrors++;
            err.println("Input file does not exist: " + options.inputPath().toAbsolutePath());
            return summary;
        }

        TransferBundle bundle;
        try {
            bundle = OBJECT_MAPPER.readValue(options.inputPath().toFile(), TransferBundle.class);
        } catch (Exception e) {
            summary.validationErrors++;
            err.println("Invalid JSON: " + e.getMessage());
            return summary;
        }

        List<String> validationErrors = validateBundle(bundle, TransferFeature.QUIZZES);
        summary.validationErrors += validationErrors.size();
        if (!validationErrors.isEmpty()) {
            for (String validationError : validationErrors) {
                err.println("Validation error: " + validationError);
            }
            return summary;
        }

        for (TransferBook book : nullSafeList(bundle.books())) {
            summary.booksScanned++;
            Optional<String> localBookId = findBookId(connection, book.source(), book.sourceId());
            if (localBookId.isEmpty()) {
                summary.booksMissing++;
                out.println("Book not found locally: " + book.source() + "/" + book.sourceId());
                continue;
            }

            summary.booksMatched++;
            Map<Integer, String> chaptersByIndex = findChapterIdsByIndex(connection, localBookId.get());

            for (TransferQuiz quiz : nullSafeList(book.quizzes())) {
                String chapterId = chaptersByIndex.get(quiz.chapterIndex());
                if (chapterId == null) {
                    summary.chaptersMissing++;
                    out.println("Chapter missing for book " + book.source() + "/" + book.sourceId()
                            + " chapterIndex=" + quiz.chapterIndex());
                    continue;
                }

                Optional<String> existingQuizId = findQuizIdByChapter(connection, chapterId);
                if (existingQuizId.isPresent() && options.onConflict() == OnConflict.SKIP) {
                    summary.quizzesSkippedConflicts++;
                    continue;
                }

                LocalDateTime generatedAt = parseDateTime(quiz.generatedAt());
                LocalDateTime updatedAt = parseDateTime(quiz.updatedAt());
                if (updatedAt == null) {
                    updatedAt = generatedAt != null ? generatedAt : LocalDateTime.now(ZoneOffset.UTC);
                }

                if (options.apply()) {
                    if (existingQuizId.isPresent()) {
                        updateQuiz(connection, existingQuizId.get(), quiz, generatedAt, updatedAt);
                    } else {
                        insertQuiz(connection, chapterId, quiz, generatedAt, updatedAt);
                    }
                }

                summary.quizzesImported++;
            }
        }

        if (!options.apply()) {
            out.println("Dry-run: no database writes applied (use --apply to persist changes).");
        }
        return summary;
    }

    static Summary importIllustrations(Connection connection, ImportOptions options, PrintStream out, PrintStream err)
            throws IOException, SQLException {
        Summary summary = new Summary();

        if (!Files.exists(options.inputPath())) {
            summary.validationErrors++;
            err.println("Input file does not exist: " + options.inputPath().toAbsolutePath());
            return summary;
        }

        TransferBundle bundle;
        try {
            bundle = OBJECT_MAPPER.readValue(options.inputPath().toFile(), TransferBundle.class);
        } catch (Exception e) {
            summary.validationErrors++;
            err.println("Invalid JSON: " + e.getMessage());
            return summary;
        }

        List<String> validationErrors = validateBundle(bundle, TransferFeature.ILLUSTRATIONS);
        summary.validationErrors += validationErrors.size();
        if (!validationErrors.isEmpty()) {
            for (String validationError : validationErrors) {
                err.println("Validation error: " + validationError);
            }
            return summary;
        }

        for (TransferBook book : nullSafeList(bundle.books())) {
            summary.booksScanned++;
            Optional<String> localBookId = findBookId(connection, book.source(), book.sourceId());
            if (localBookId.isEmpty()) {
                summary.booksMissing++;
                out.println("Book not found locally: " + book.source() + "/" + book.sourceId());
                continue;
            }

            summary.booksMatched++;
            Map<Integer, String> chaptersByIndex = findChapterIdsByIndex(connection, localBookId.get());

            for (TransferIllustration illustration : nullSafeList(book.illustrations())) {
                String chapterId = chaptersByIndex.get(illustration.chapterIndex());
                if (chapterId == null) {
                    summary.chaptersMissing++;
                    out.println("Chapter missing for book " + book.source() + "/" + book.sourceId()
                            + " chapterIndex=" + illustration.chapterIndex());
                    continue;
                }

                Optional<String> existingIllustrationId = findIllustrationIdByChapter(connection, chapterId);
                if (existingIllustrationId.isPresent() && options.onConflict() == OnConflict.SKIP) {
                    summary.illustrationsSkippedConflicts++;
                    continue;
                }

                LocalDateTime completedAt = parseDateTime(illustration.completedAt());
                if (options.apply()) {
                    if (existingIllustrationId.isPresent()) {
                        updateIllustration(connection, existingIllustrationId.get(), illustration, completedAt);
                    } else {
                        insertIllustration(connection, chapterId, illustration, completedAt);
                    }
                }
                summary.illustrationsImported++;
            }
        }

        if (!options.apply()) {
            out.println("Dry-run: no database writes applied (use --apply to persist changes).");
        }
        return summary;
    }

    static Summary importPortraits(Connection connection, ImportOptions options, PrintStream out, PrintStream err)
            throws IOException, SQLException {
        Summary summary = new Summary();

        if (!Files.exists(options.inputPath())) {
            summary.validationErrors++;
            err.println("Input file does not exist: " + options.inputPath().toAbsolutePath());
            return summary;
        }

        TransferBundle bundle;
        try {
            bundle = OBJECT_MAPPER.readValue(options.inputPath().toFile(), TransferBundle.class);
        } catch (Exception e) {
            summary.validationErrors++;
            err.println("Invalid JSON: " + e.getMessage());
            return summary;
        }

        List<String> validationErrors = validateBundle(bundle, TransferFeature.PORTRAITS);
        summary.validationErrors += validationErrors.size();
        if (!validationErrors.isEmpty()) {
            for (String validationError : validationErrors) {
                err.println("Validation error: " + validationError);
            }
            return summary;
        }

        for (TransferBook book : nullSafeList(bundle.books())) {
            summary.booksScanned++;
            Optional<String> localBookId = findBookId(connection, book.source(), book.sourceId());
            if (localBookId.isEmpty()) {
                summary.booksMissing++;
                out.println("Book not found locally: " + book.source() + "/" + book.sourceId());
                continue;
            }

            summary.booksMatched++;
            Map<Integer, String> chaptersByIndex = findChapterIdsByIndex(connection, localBookId.get());

            for (TransferPortrait portrait : nullSafeList(book.portraits())) {
                String firstChapterId = chaptersByIndex.get(portrait.firstChapterIndex());
                if (firstChapterId == null) {
                    summary.chaptersMissing++;
                    out.println("First chapter missing for portrait in book " + book.source() + "/" + book.sourceId()
                            + " chapterIndex=" + portrait.firstChapterIndex() + " name=" + portrait.name());
                    continue;
                }

                Optional<String> existingCharacterId = findCharacterIdByBookAndName(connection, localBookId.get(), portrait.name());
                if (existingCharacterId.isPresent() && options.onConflict() == OnConflict.SKIP) {
                    summary.portraitsSkippedConflicts++;
                    continue;
                }

                LocalDateTime createdAt = parseDateTime(portrait.createdAt());
                if (createdAt == null) {
                    createdAt = LocalDateTime.now(ZoneOffset.UTC);
                }
                LocalDateTime completedAt = parseDateTime(portrait.completedAt());
                if (options.apply()) {
                    if (existingCharacterId.isPresent()) {
                        updatePortrait(connection, existingCharacterId.get(), firstChapterId, portrait, createdAt, completedAt);
                    } else {
                        insertPortrait(connection, localBookId.get(), firstChapterId, portrait, createdAt, completedAt);
                    }
                }
                summary.portraitsImported++;
            }
        }

        if (!options.apply()) {
            out.println("Dry-run: no database writes applied (use --apply to persist changes).");
        }
        return summary;
    }

    private static List<String> validateBundle(TransferBundle bundle, TransferFeature feature) {
        List<String> errors = new ArrayList<>();
        if (bundle == null) {
            errors.add("Input is empty.");
            return errors;
        }
        if (!FORMAT_VERSION.equals(bundle.formatVersion())) {
            errors.add("Unsupported formatVersion: " + bundle.formatVersion());
        }
        String expectedFeature = feature.value();
        if (bundle.features() == null || !bundle.features().contains(expectedFeature)) {
            errors.add("features must include '" + expectedFeature + "'.");
        }
        if (bundle.books() == null) {
            errors.add("books must be present.");
            return errors;
        }

        for (int i = 0; i < bundle.books().size(); i++) {
            TransferBook book = bundle.books().get(i);
            String bookPrefix = "books[" + i + "]";
            if (book == null) {
                errors.add(bookPrefix + " must not be null.");
                continue;
            }
            if (isBlank(book.source())) {
                errors.add(bookPrefix + ".source is required.");
            }
            if (isBlank(book.sourceId())) {
                errors.add(bookPrefix + ".sourceId is required.");
            }
            switch (feature) {
                case RECAPS -> {
                    for (int j = 0; j < nullSafeList(book.recaps()).size(); j++) {
                        TransferRecap recap = nullSafeList(book.recaps()).get(j);
                        String recapPrefix = bookPrefix + ".recaps[" + j + "]";
                        if (recap == null) {
                            errors.add(recapPrefix + " must not be null.");
                            continue;
                        }
                        if (recap.chapterIndex() < 0) {
                            errors.add(recapPrefix + ".chapterIndex must be >= 0.");
                        }
                        if (isBlank(recap.payloadJson())) {
                            errors.add(recapPrefix + ".payloadJson is required.");
                        }
                        String status = recap.status();
                        if (!isBlank(status) && !ChapterStatus.COMPLETED.value().equalsIgnoreCase(status.trim())) {
                            errors.add(recapPrefix + ".status must be COMPLETED.");
                        }
                        if (!isBlank(recap.generatedAt()) && parseDateTimeSafe(recap.generatedAt()) == null) {
                            errors.add(recapPrefix + ".generatedAt is not a valid timestamp.");
                        }
                        if (!isBlank(recap.updatedAt()) && parseDateTimeSafe(recap.updatedAt()) == null) {
                            errors.add(recapPrefix + ".updatedAt is not a valid timestamp.");
                        }
                    }
                }
                case QUIZZES -> {
                    for (int j = 0; j < nullSafeList(book.quizzes()).size(); j++) {
                        TransferQuiz quiz = nullSafeList(book.quizzes()).get(j);
                        String quizPrefix = bookPrefix + ".quizzes[" + j + "]";
                        if (quiz == null) {
                            errors.add(quizPrefix + " must not be null.");
                            continue;
                        }
                        if (quiz.chapterIndex() < 0) {
                            errors.add(quizPrefix + ".chapterIndex must be >= 0.");
                        }
                        if (isBlank(quiz.payloadJson())) {
                            errors.add(quizPrefix + ".payloadJson is required.");
                        }
                        String status = quiz.status();
                        if (!isBlank(status) && !ChapterStatus.COMPLETED.value().equalsIgnoreCase(status.trim())) {
                            errors.add(quizPrefix + ".status must be COMPLETED.");
                        }
                        if (!isBlank(quiz.generatedAt()) && parseDateTimeSafe(quiz.generatedAt()) == null) {
                            errors.add(quizPrefix + ".generatedAt is not a valid timestamp.");
                        }
                        if (!isBlank(quiz.updatedAt()) && parseDateTimeSafe(quiz.updatedAt()) == null) {
                            errors.add(quizPrefix + ".updatedAt is not a valid timestamp.");
                        }
                    }
                }
                case ILLUSTRATIONS -> {
                    for (int j = 0; j < nullSafeList(book.illustrations()).size(); j++) {
                        TransferIllustration illustration = nullSafeList(book.illustrations()).get(j);
                        String illustrationPrefix = bookPrefix + ".illustrations[" + j + "]";
                        if (illustration == null) {
                            errors.add(illustrationPrefix + " must not be null.");
                            continue;
                        }
                        if (illustration.chapterIndex() < 0) {
                            errors.add(illustrationPrefix + ".chapterIndex must be >= 0.");
                        }
                        if (isBlank(illustration.imageFilename())) {
                            errors.add(illustrationPrefix + ".imageFilename is required.");
                        }
                        String status = illustration.status();
                        if (!isBlank(status) && !ChapterStatus.COMPLETED.value().equalsIgnoreCase(status.trim())) {
                            errors.add(illustrationPrefix + ".status must be COMPLETED.");
                        }
                        if (!isBlank(illustration.completedAt()) && parseDateTimeSafe(illustration.completedAt()) == null) {
                            errors.add(illustrationPrefix + ".completedAt is not a valid timestamp.");
                        }
                    }
                }
                case PORTRAITS -> {
                    for (int j = 0; j < nullSafeList(book.portraits()).size(); j++) {
                        TransferPortrait portrait = nullSafeList(book.portraits()).get(j);
                        String portraitPrefix = bookPrefix + ".portraits[" + j + "]";
                        if (portrait == null) {
                            errors.add(portraitPrefix + " must not be null.");
                            continue;
                        }
                        if (isBlank(portrait.name())) {
                            errors.add(portraitPrefix + ".name is required.");
                        }
                        if (portrait.firstChapterIndex() < 0) {
                            errors.add(portraitPrefix + ".firstChapterIndex must be >= 0.");
                        }
                        if (portrait.firstParagraphIndex() < 0) {
                            errors.add(portraitPrefix + ".firstParagraphIndex must be >= 0.");
                        }
                        if (isBlank(portrait.portraitFilename())) {
                            errors.add(portraitPrefix + ".portraitFilename is required.");
                        }
                        String status = portrait.status();
                        if (!isBlank(status) && !ChapterStatus.COMPLETED.value().equalsIgnoreCase(status.trim())) {
                            errors.add(portraitPrefix + ".status must be COMPLETED.");
                        }
                        if (!isBlank(portrait.createdAt()) && parseDateTimeSafe(portrait.createdAt()) == null) {
                            errors.add(portraitPrefix + ".createdAt is not a valid timestamp.");
                        }
                        if (!isBlank(portrait.completedAt()) && parseDateTimeSafe(portrait.completedAt()) == null) {
                            errors.add(portraitPrefix + ".completedAt is not a valid timestamp.");
                        }
                    }
                }
            }
        }
        return errors;
    }

    private static List<RecapExportRow> queryCompletedRecapRows(Connection connection) throws SQLException {
        String sql = """
                select b.id as book_id, b.source, b.source_id, b.title, b.author,
                       c.chapter_index, cr.prompt_version, cr.model_name,
                       cr.generated_at, cr.updated_at, cr.payload_json
                from chapter_recaps cr
                join chapters c on cr.chapter_id = c.id
                join books b on c.book_id = b.id
                where cr.status = 'COMPLETED'
                  and cr.payload_json is not null
                  and trim(cr.payload_json) <> ''
                order by b.source, b.source_id, c.chapter_index
                """;
        List<RecapExportRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(new RecapExportRow(
                        rs.getString("book_id"),
                        rs.getString("source"),
                        rs.getString("source_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getInt("chapter_index"),
                        rs.getString("prompt_version"),
                        rs.getString("model_name"),
                        rs.getObject("generated_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getString("payload_json")
                ));
            }
        }
        return rows;
    }

    private static List<QuizExportRow> queryCompletedQuizRows(Connection connection) throws SQLException {
        String sql = """
                select b.id as book_id, b.source, b.source_id, b.title, b.author,
                       c.chapter_index, cq.prompt_version, cq.model_name,
                       cq.generated_at, cq.updated_at, cq.payload_json
                from chapter_quizzes cq
                join chapters c on cq.chapter_id = c.id
                join books b on c.book_id = b.id
                where cq.status = 'COMPLETED'
                  and cq.payload_json is not null
                  and trim(cq.payload_json) <> ''
                order by b.source, b.source_id, c.chapter_index
                """;
        List<QuizExportRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(new QuizExportRow(
                        rs.getString("book_id"),
                        rs.getString("source"),
                        rs.getString("source_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getInt("chapter_index"),
                        rs.getString("prompt_version"),
                        rs.getString("model_name"),
                        rs.getObject("generated_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getString("payload_json")
                ));
            }
        }
        return rows;
    }

    private static List<IllustrationExportRow> queryCompletedIllustrationRows(Connection connection) throws SQLException {
        String sql = """
                select b.id as book_id, b.source, b.source_id, b.title, b.author,
                       c.chapter_index, i.image_filename, i.generated_prompt, i.completed_at
                from illustrations i
                join chapters c on i.chapter_id = c.id
                join books b on c.book_id = b.id
                where i.status = 'COMPLETED'
                  and i.image_filename is not null
                  and trim(i.image_filename) <> ''
                order by b.source, b.source_id, c.chapter_index
                """;
        List<IllustrationExportRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(new IllustrationExportRow(
                        rs.getString("book_id"),
                        rs.getString("source"),
                        rs.getString("source_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getInt("chapter_index"),
                        rs.getString("image_filename"),
                        rs.getString("generated_prompt"),
                        rs.getObject("completed_at", LocalDateTime.class)
                ));
            }
        }
        return rows;
    }

    private static List<PortraitExportRow> queryCompletedPortraitRows(Connection connection) throws SQLException {
        String sql = """
                select b.id as book_id, b.source, b.source_id, b.title, b.author,
                       ch.name, ch.description, ch.character_type,
                       fc.chapter_index as first_chapter_index, ch.first_paragraph_index,
                       ch.portrait_filename, ch.portrait_prompt, ch.created_at, ch.completed_at
                from characters ch
                join books b on ch.book_id = b.id
                join chapters fc on ch.first_chapter_id = fc.id
                where ch.status = 'COMPLETED'
                  and ch.portrait_filename is not null
                  and trim(ch.portrait_filename) <> ''
                order by b.source, b.source_id, fc.chapter_index, ch.first_paragraph_index, lower(ch.name)
                """;
        List<PortraitExportRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(new PortraitExportRow(
                        rs.getString("book_id"),
                        rs.getString("source"),
                        rs.getString("source_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("character_type"),
                        rs.getInt("first_chapter_index"),
                        rs.getInt("first_paragraph_index"),
                        rs.getString("portrait_filename"),
                        rs.getString("portrait_prompt"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("completed_at", LocalDateTime.class)
                ));
            }
        }
        return rows;
    }

    private static Map<String, List<BookRow>> findBooksBySourceId(Connection connection, Set<String> sourceIds)
            throws SQLException {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }

        String sql = "select id, source, source_id, title, author from books where source_id is not null";
        Map<String, List<BookRow>> bySourceId = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String sourceId = rs.getString("source_id");
                if (!sourceIds.contains(sourceId)) {
                    continue;
                }
                bySourceId.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(
                        new BookRow(
                                rs.getString("id"),
                                rs.getString("source"),
                                sourceId,
                                rs.getString("title"),
                                rs.getString("author")
                        )
                );
            }
        }
        return bySourceId;
    }

    private static Optional<String> findBookId(Connection connection, String source, String sourceId) throws SQLException {
        String sql = "select id from books where source = ? and source_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            statement.setString(2, sourceId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    private static Map<Integer, String> findChapterIdsByIndex(Connection connection, String bookId) throws SQLException {
        String sql = "select id, chapter_index from chapters where book_id = ?";
        Map<Integer, String> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bookId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getInt("chapter_index"), rs.getString("id"));
                }
            }
        }
        return result;
    }

    private static Optional<String> findRecapIdByChapter(Connection connection, String chapterId) throws SQLException {
        String sql = "select id from chapter_recaps where chapter_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chapterId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findQuizIdByChapter(Connection connection, String chapterId) throws SQLException {
        String sql = "select id from chapter_quizzes where chapter_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chapterId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findIllustrationIdByChapter(Connection connection, String chapterId) throws SQLException {
        String sql = "select id from illustrations where chapter_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chapterId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findCharacterIdByBookAndName(Connection connection, String bookId, String name) throws SQLException {
        String sql = "select id from characters where book_id = ? and name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bookId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    private static void insertRecap(Connection connection, String chapterId, TransferRecap recap,
                                    LocalDateTime generatedAt, LocalDateTime updatedAt) throws SQLException {
        String sql = """
                insert into chapter_recaps (
                    id, chapter_id, status, updated_at, generated_at,
                    prompt_version, model_name, payload_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, chapterId);
            statement.setString(3, ChapterStatus.COMPLETED.value());
            statement.setTimestamp(4, Timestamp.valueOf(updatedAt));
            if (generatedAt == null) {
                statement.setTimestamp(5, null);
            } else {
                statement.setTimestamp(5, Timestamp.valueOf(generatedAt));
            }
            statement.setString(6, blankToNull(recap.promptVersion()));
            statement.setString(7, blankToNull(recap.modelName()));
            statement.setString(8, recap.payloadJson());
            statement.executeUpdate();
        }
    }

    private static void updateRecap(Connection connection, String recapId, TransferRecap recap,
                                    LocalDateTime generatedAt, LocalDateTime updatedAt) throws SQLException {
        String sql = """
                update chapter_recaps
                set status = ?, updated_at = ?, generated_at = ?,
                    prompt_version = ?, model_name = ?, payload_json = ?
                where id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChapterStatus.COMPLETED.value());
            statement.setTimestamp(2, Timestamp.valueOf(updatedAt));
            if (generatedAt == null) {
                statement.setTimestamp(3, null);
            } else {
                statement.setTimestamp(3, Timestamp.valueOf(generatedAt));
            }
            statement.setString(4, blankToNull(recap.promptVersion()));
            statement.setString(5, blankToNull(recap.modelName()));
            statement.setString(6, recap.payloadJson());
            statement.setString(7, recapId);
            statement.executeUpdate();
        }
    }

    private static void insertQuiz(Connection connection, String chapterId, TransferQuiz quiz,
                                   LocalDateTime generatedAt, LocalDateTime updatedAt) throws SQLException {
        String sql = """
                insert into chapter_quizzes (
                    id, chapter_id, status, updated_at, generated_at,
                    prompt_version, model_name, payload_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, chapterId);
            statement.setString(3, ChapterStatus.COMPLETED.value());
            statement.setTimestamp(4, Timestamp.valueOf(updatedAt));
            if (generatedAt == null) {
                statement.setTimestamp(5, null);
            } else {
                statement.setTimestamp(5, Timestamp.valueOf(generatedAt));
            }
            statement.setString(6, blankToNull(quiz.promptVersion()));
            statement.setString(7, blankToNull(quiz.modelName()));
            statement.setString(8, quiz.payloadJson());
            statement.executeUpdate();
        }
    }

    private static void updateQuiz(Connection connection, String quizId, TransferQuiz quiz,
                                   LocalDateTime generatedAt, LocalDateTime updatedAt) throws SQLException {
        String sql = """
                update chapter_quizzes
                set status = ?, updated_at = ?, generated_at = ?,
                    prompt_version = ?, model_name = ?, payload_json = ?
                where id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChapterStatus.COMPLETED.value());
            statement.setTimestamp(2, Timestamp.valueOf(updatedAt));
            if (generatedAt == null) {
                statement.setTimestamp(3, null);
            } else {
                statement.setTimestamp(3, Timestamp.valueOf(generatedAt));
            }
            statement.setString(4, blankToNull(quiz.promptVersion()));
            statement.setString(5, blankToNull(quiz.modelName()));
            statement.setString(6, quiz.payloadJson());
            statement.setString(7, quizId);
            statement.executeUpdate();
        }
    }

    private static void insertIllustration(Connection connection, String chapterId, TransferIllustration illustration,
                                           LocalDateTime completedAt) throws SQLException {
        String sql = """
                insert into illustrations (
                    id, chapter_id, status, image_filename, generated_prompt,
                    created_at, completed_at, retry_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, chapterId);
            statement.setString(3, ChapterStatus.COMPLETED.value());
            statement.setString(4, illustration.imageFilename().trim());
            statement.setString(5, blankToNull(illustration.generatedPrompt()));
            LocalDateTime createdAt = completedAt != null ? completedAt : LocalDateTime.now(ZoneOffset.UTC);
            statement.setTimestamp(6, Timestamp.valueOf(createdAt));
            if (completedAt == null) {
                statement.setTimestamp(7, null);
            } else {
                statement.setTimestamp(7, Timestamp.valueOf(completedAt));
            }
            statement.setInt(8, 0);
            statement.executeUpdate();
        }
    }

    private static void updateIllustration(Connection connection, String illustrationId, TransferIllustration illustration,
                                           LocalDateTime completedAt) throws SQLException {
        String sql = """
                update illustrations
                set status = ?, image_filename = ?, generated_prompt = ?, completed_at = ?,
                    error_message = null, next_retry_at = null, retry_count = 0
                where id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ChapterStatus.COMPLETED.value());
            statement.setString(2, illustration.imageFilename().trim());
            statement.setString(3, blankToNull(illustration.generatedPrompt()));
            if (completedAt == null) {
                statement.setTimestamp(4, null);
            } else {
                statement.setTimestamp(4, Timestamp.valueOf(completedAt));
            }
            statement.setString(5, illustrationId);
            statement.executeUpdate();
        }
    }

    private static void insertPortrait(Connection connection, String bookId, String firstChapterId, TransferPortrait portrait,
                                       LocalDateTime createdAt, LocalDateTime completedAt) throws SQLException {
        String sql = """
                insert into characters (
                    id, book_id, name, description, first_chapter_id, first_paragraph_index,
                    portrait_filename, portrait_prompt, status, character_type,
                    created_at, completed_at, retry_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, bookId);
            statement.setString(3, portrait.name().trim());
            statement.setString(4, blankToNull(portrait.description()));
            statement.setString(5, firstChapterId);
            statement.setInt(6, Math.max(0, portrait.firstParagraphIndex()));
            statement.setString(7, portrait.portraitFilename().trim());
            statement.setString(8, blankToNull(portrait.portraitPrompt()));
            statement.setString(9, ChapterStatus.COMPLETED.value());
            statement.setString(10, resolveCharacterType(portrait.characterType()));
            statement.setTimestamp(11, Timestamp.valueOf(createdAt));
            if (completedAt == null) {
                statement.setTimestamp(12, null);
            } else {
                statement.setTimestamp(12, Timestamp.valueOf(completedAt));
            }
            statement.setInt(13, 0);
            statement.executeUpdate();
        }
    }

    private static void updatePortrait(Connection connection, String characterId, String firstChapterId, TransferPortrait portrait,
                                       LocalDateTime createdAt, LocalDateTime completedAt) throws SQLException {
        String sql = """
                update characters
                set description = ?, first_chapter_id = ?, first_paragraph_index = ?,
                    portrait_filename = ?, portrait_prompt = ?, status = ?, character_type = ?,
                    created_at = ?, completed_at = ?, error_message = null,
                    next_retry_at = null, retry_count = 0
                where id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, blankToNull(portrait.description()));
            statement.setString(2, firstChapterId);
            statement.setInt(3, Math.max(0, portrait.firstParagraphIndex()));
            statement.setString(4, portrait.portraitFilename().trim());
            statement.setString(5, blankToNull(portrait.portraitPrompt()));
            statement.setString(6, ChapterStatus.COMPLETED.value());
            statement.setString(7, resolveCharacterType(portrait.characterType()));
            statement.setTimestamp(8, Timestamp.valueOf(createdAt));
            if (completedAt == null) {
                statement.setTimestamp(9, null);
            } else {
                statement.setTimestamp(9, Timestamp.valueOf(completedAt));
            }
            statement.setString(10, characterId);
            statement.executeUpdate();
        }
    }

    private static String resolveCharacterType(String rawType) {
        if (isBlank(rawType)) {
            return "SECONDARY";
        }
        String normalized = rawType.trim().toUpperCase();
        if ("PRIMARY".equals(normalized) || "SECONDARY".equals(normalized)) {
            return normalized;
        }
        return "SECONDARY";
    }

    private static String formatDateTime(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(value.toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime parseDateTime(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            Instant instant = Instant.parse(value);
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        try {
            OffsetDateTime offsetDateTime = OffsetDateTime.parse(value);
            return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
        }
        return LocalDateTime.parse(value);
    }

    private static LocalDateTime parseDateTimeSafe(String raw) {
        try {
            return parseDateTime(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static void printSummary(String command, TransferFeature feature, Summary summary, boolean apply, PrintStream out) {
        out.println("----------");
        out.println("Cache Transfer Summary (" + command + ")");
        out.println("Mode: " + (apply ? "apply" : "dry-run"));
        out.println("Books scanned: " + summary.booksScanned());
        out.println("Books matched: " + summary.booksMatched());
        out.println("Books missing: " + summary.booksMissing());
        if (feature == TransferFeature.RECAPS) {
            if (COMMAND_EXPORT.equals(command)) {
                out.println("Recaps exported: " + summary.recapsExported());
            } else {
                out.println("Recaps imported: " + summary.recapsImported());
            }
            out.println("Recaps skipped (conflict): " + summary.recapsSkippedConflicts());
        } else if (feature == TransferFeature.QUIZZES) {
            if (COMMAND_EXPORT.equals(command)) {
                out.println("Quizzes exported: " + summary.quizzesExported());
            } else {
                out.println("Quizzes imported: " + summary.quizzesImported());
            }
            out.println("Quizzes skipped (conflict): " + summary.quizzesSkippedConflicts());
        } else if (feature == TransferFeature.ILLUSTRATIONS) {
            if (COMMAND_EXPORT.equals(command)) {
                out.println("Illustrations exported: " + summary.illustrationsExported());
            } else {
                out.println("Illustrations imported: " + summary.illustrationsImported());
            }
            out.println("Illustrations skipped (conflict): " + summary.illustrationsSkippedConflicts());
        } else {
            if (COMMAND_EXPORT.equals(command)) {
                out.println("Portraits exported: " + summary.portraitsExported());
            } else {
                out.println("Portraits imported: " + summary.portraitsImported());
            }
            out.println("Portraits skipped (conflict): " + summary.portraitsSkippedConflicts());
        }
        out.println("Chapters missing: " + summary.chaptersMissing());
        out.println("Validation errors: " + summary.validationErrors());
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  CacheTransferRunner export --feature recaps|quizzes|illustrations|portraits (--all-cached | --book-source-id <id>[,<id>...]) [--output <path>] [--apply]");
        out.println("  CacheTransferRunner import --feature recaps|quizzes|illustrations|portraits --input <path> [--on-conflict skip|overwrite] [--apply]");
        out.println("");
        out.println("Notes:");
        out.println("  --apply is required to write changes. Without --apply, commands run in dry-run mode.");
        out.println("  Supported features: recaps, quizzes, illustrations, portraits.");
    }

    private static Set<String> parseCsvSet(String raw) {
        if (isBlank(raw)) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = CacheTransferRunner.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
                return properties;
            }
        } catch (IOException e) {
            System.err.println("Failed to load application.properties from classpath: " + e.getMessage());
        }

        Path fallbackPath = Path.of("src/main/resources/application.properties");
        if (Files.exists(fallbackPath)) {
            try (InputStream input = Files.newInputStream(fallbackPath)) {
                properties.load(input);
            } catch (IOException e) {
                System.err.println("Failed to load application.properties from disk: " + e.getMessage());
            }
        }
        return properties;
    }

    private static ParsedArgs parseArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new ParsedArgs(null, Map.of(), Set.of());
        }

        String command = null;
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        Set<String> allOptions = new LinkedHashSet<>();

        List<String> tokens = Arrays.asList(args);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("--")) {
                String option = token;
                String value = null;
                int equalsIndex = token.indexOf('=');
                if (equalsIndex > 0) {
                    option = token.substring(0, equalsIndex);
                    value = token.substring(equalsIndex + 1);
                } else if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--")) {
                    value = tokens.get(i + 1);
                    i++;
                }

                allOptions.add(option);
                if (value == null) {
                    flags.add(option);
                } else {
                    values.put(option, value);
                }
                continue;
            }

            if (command == null) {
                command = token.trim().toLowerCase();
            } else {
                throw new IllegalArgumentException("Unexpected positional argument: " + token);
            }
        }

        return new ParsedArgs(command, values, flags, allOptions);
    }

    private static <T> List<T> nullSafeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    record ParsedArgs(String command, Map<String, String> values, Set<String> flags, Set<String> allOptions) {
        ParsedArgs(String command, Map<String, String> values, Set<String> flags) {
            this(command, values, flags, Set.of());
        }

        Optional<String> optionValue(String key) {
            return Optional.ofNullable(values.get(key));
        }
    }

    record DbConfig(String url, String user, String password) {
    }

    record ExportOptions(boolean apply, boolean allCached, Set<String> sourceIds, Path outputPath) {
    }

    record ImportOptions(boolean apply, Path inputPath, OnConflict onConflict) {
    }

    enum OnConflict {
        SKIP("skip"),
        OVERWRITE("overwrite");

        private final String value;

        OnConflict(String value) {
            this.value = value;
        }

        static Optional<OnConflict> fromValue(String value) {
            for (OnConflict conflict : values()) {
                if (conflict.value.equals(value)) {
                    return Optional.of(conflict);
                }
            }
            return Optional.empty();
        }
    }

    enum ChapterStatus {
        COMPLETED("COMPLETED");

        private final String value;

        ChapterStatus(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    enum TransferFeature {
        RECAPS(FEATURE_RECAPS),
        QUIZZES(FEATURE_QUIZZES),
        ILLUSTRATIONS(FEATURE_ILLUSTRATIONS),
        PORTRAITS(FEATURE_PORTRAITS);

        private final String value;

        TransferFeature(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        static Optional<TransferFeature> fromValue(String raw) {
            if (isBlank(raw)) {
                return Optional.empty();
            }
            for (TransferFeature feature : values()) {
                if (feature.value.equalsIgnoreCase(raw.trim())) {
                    return Optional.of(feature);
                }
            }
            return Optional.empty();
        }
    }

    static class Summary {
        private int booksScanned;
        private int booksMatched;
        private int booksMissing;
        private int recapsExported;
        private int recapsImported;
        private int recapsSkippedConflicts;
        private int quizzesExported;
        private int quizzesImported;
        private int quizzesSkippedConflicts;
        private int illustrationsExported;
        private int illustrationsImported;
        private int illustrationsSkippedConflicts;
        private int portraitsExported;
        private int portraitsImported;
        private int portraitsSkippedConflicts;
        private int chaptersMissing;
        private int validationErrors;

        int booksScanned() {
            return booksScanned;
        }

        int booksMatched() {
            return booksMatched;
        }

        int booksMissing() {
            return booksMissing;
        }

        int recapsExported() {
            return recapsExported;
        }

        int recapsImported() {
            return recapsImported;
        }

        int recapsSkippedConflicts() {
            return recapsSkippedConflicts;
        }

        int quizzesExported() {
            return quizzesExported;
        }

        int quizzesImported() {
            return quizzesImported;
        }

        int quizzesSkippedConflicts() {
            return quizzesSkippedConflicts;
        }

        int illustrationsExported() {
            return illustrationsExported;
        }

        int illustrationsImported() {
            return illustrationsImported;
        }

        int illustrationsSkippedConflicts() {
            return illustrationsSkippedConflicts;
        }

        int portraitsExported() {
            return portraitsExported;
        }

        int portraitsImported() {
            return portraitsImported;
        }

        int portraitsSkippedConflicts() {
            return portraitsSkippedConflicts;
        }

        int chaptersMissing() {
            return chaptersMissing;
        }

        int validationErrors() {
            return validationErrors;
        }
    }

    record BookRow(String id, String source, String sourceId, String title, String author) {
    }

    record RecapExportRow(
            String bookId,
            String source,
            String sourceId,
            String title,
            String author,
            int chapterIndex,
            String promptVersion,
            String modelName,
            LocalDateTime generatedAt,
            LocalDateTime updatedAt,
            String payloadJson
    ) {
    }

    record QuizExportRow(
            String bookId,
            String source,
            String sourceId,
            String title,
            String author,
            int chapterIndex,
            String promptVersion,
            String modelName,
            LocalDateTime generatedAt,
            LocalDateTime updatedAt,
            String payloadJson
    ) {
    }

    record IllustrationExportRow(
            String bookId,
            String source,
            String sourceId,
            String title,
            String author,
            int chapterIndex,
            String imageFilename,
            String generatedPrompt,
            LocalDateTime completedAt
    ) {
    }

    record PortraitExportRow(
            String bookId,
            String source,
            String sourceId,
            String title,
            String author,
            String name,
            String description,
            String characterType,
            int firstChapterIndex,
            int firstParagraphIndex,
            String portraitFilename,
            String portraitPrompt,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }

    static class TransferBookBuilder {
        private final String source;
        private final String sourceId;
        private final String title;
        private final String author;
        private final List<TransferRecap> recaps = new ArrayList<>();
        private final List<TransferQuiz> quizzes = new ArrayList<>();
        private final List<TransferIllustration> illustrations = new ArrayList<>();
        private final List<TransferPortrait> portraits = new ArrayList<>();

        TransferBookBuilder(String source, String sourceId, String title, String author) {
            this.source = source;
            this.sourceId = sourceId;
            this.title = title;
            this.author = author;
        }

        List<TransferRecap> recaps() {
            return recaps;
        }

        List<TransferQuiz> quizzes() {
            return quizzes;
        }

        List<TransferIllustration> illustrations() {
            return illustrations;
        }

        List<TransferPortrait> portraits() {
            return portraits;
        }

        TransferBook build() {
            return new TransferBook(source, sourceId, title, author, recaps, quizzes, illustrations, portraits);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferBundle(
            String formatVersion,
            String exportedAt,
            List<String> features,
            List<TransferBook> books
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferBook(
            String source,
            String sourceId,
            String title,
            String author,
            List<TransferRecap> recaps,
            List<TransferQuiz> quizzes,
            List<TransferIllustration> illustrations,
            List<TransferPortrait> portraits
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferRecap(
            int chapterIndex,
            String status,
            String promptVersion,
            String modelName,
            String generatedAt,
            String updatedAt,
            String payloadJson
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferQuiz(
            int chapterIndex,
            String status,
            String promptVersion,
            String modelName,
            String generatedAt,
            String updatedAt,
            String payloadJson
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferIllustration(
            int chapterIndex,
            String status,
            String imageFilename,
            String generatedPrompt,
            String completedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferPortrait(
            String name,
            String description,
            String characterType,
            int firstChapterIndex,
            int firstParagraphIndex,
            String status,
            String portraitFilename,
            String portraitPrompt,
            String createdAt,
            String completedAt
    ) {
    }
}
