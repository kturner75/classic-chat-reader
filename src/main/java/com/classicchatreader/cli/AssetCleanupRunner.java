package com.classicchatreader.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class AssetCleanupRunner {

    private static final String APPLY_FLAG = "--apply";
    private static final String AUDIO_FLAG = "--audio";
    private static final String AUDIO_ROOT_FLAG = "--audio-root-cache";
    private static final String LEGACY_ONLY_FLAG = "--legacy-only";
    private static final String HELP_FLAG = "--help";

    private static final int SAMPLE_LIMIT = 20;

    public static void main(String[] args) {
        List<String> arguments = Arrays.asList(args);
        if (arguments.contains(HELP_FLAG)) {
            printUsage();
            return;
        }

        boolean apply = arguments.contains(APPLY_FLAG);
        boolean includeAudio = arguments.contains(AUDIO_FLAG);
        boolean includeAudioRoot = arguments.contains(AUDIO_ROOT_FLAG);
        boolean legacyOnly = arguments.contains(LEGACY_ONLY_FLAG);

        Properties properties = loadProperties();
        String dbUrl = firstNonBlank(
                System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("DATABASE_URL"),
                properties.getProperty("spring.datasource.url"),
                "jdbc:h2:file:./data/library");
        String dbUser = firstNonBlank(
                System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("DATABASE_USERNAME"),
                properties.getProperty("spring.datasource.username"),
                "sa");
        String dbPassword = firstNonBlank(
                System.getenv("SPRING_DATASOURCE_PASSWORD"),
                System.getenv("DATABASE_PASSWORD"),
                properties.getProperty("spring.datasource.password"),
                "");

        Path illustrationDir = Path.of(properties.getProperty("illustration.cache-dir", "./data/illustrations"));
        Path portraitDir = Path.of(properties.getProperty("character.portrait.cache-dir", "./data/character-portraits"));
        Path audioDir = Path.of(properties.getProperty("tts.cache-dir", "./data/audio"));

        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            Set<String> illustrationFiles = queryFilenames(connection, "select image_filename from illustrations where image_filename is not null");
            Set<String> portraitFiles = queryFilenames(connection, "select portrait_filename from characters where portrait_filename is not null");
            BookKeyData bookKeyData = includeAudio ? queryBookKeys(connection) : new BookKeyData();
            BookChapterData chapterData = includeAudio ? queryBookChapters(connection, bookKeyData.bookKeysById()) : new BookChapterData();

            List<Path> orphanIllustrations = findOrphanedFiles(illustrationDir, illustrationFiles, legacyOnly);
            List<Path> orphanPortraits = findOrphanedFiles(portraitDir, portraitFiles, legacyOnly);
            List<Path> orphanAudio = includeAudio
                ? findOrphanedAudio(audioDir, bookKeyData.bookKeys(), chapterData.chaptersByBookKey(),
                                    includeAudioRoot, legacyOnly)
                : List.of();

            report("illustrations", orphanIllustrations);
            report("character portraits", orphanPortraits);
            if (includeAudio) {
                report("audio cache", orphanAudio);
            } else {
                System.out.println("Audio cleanup skipped (add --audio to enable).");
            }

            if (!apply) {
                System.out.println("Dry run only. Re-run with --apply to delete files.");
                return;
            }

            int deleted = 0;
            deleted += deletePaths(orphanIllustrations);
            deleted += deletePaths(orphanPortraits);
            deleted += deletePaths(orphanAudio);

            if (includeAudio) {
                deleted += deleteEmptyDirs(audioDir);
            }

            System.out.println("Deleted " + deleted + " paths.");
        } catch (Exception e) {
            System.err.println("Asset cleanup failed: " + e.getMessage());
        }
    }

    private static void printUsage() {
        System.out.println("Usage: AssetCleanupRunner [--apply] [--audio] [--audio-root-cache]");
        System.out.println("  --apply             Delete orphaned files (default is dry run).");
        System.out.println("  --audio             Also check TTS audio cache for missing books/chapters.");
        System.out.println("  --audio-root-cache  Include audio files stored directly under the audio cache dir.");
        System.out.println("  --legacy-only       Only delete non-stable assets (paths not under books/).");
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = AssetCleanupRunner.class.getClassLoader().getResourceAsStream("application.properties")) {
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Set<String> queryFilenames(Connection connection, String sql) throws Exception {
        Set<String> values = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static List<Path> findOrphanedFiles(Path directory, Set<String> referencedFilenames,
                                                boolean legacyOnly) throws IOException {
        List<Path> orphaned = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return orphaned;
        }
        try (var paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (legacyOnly && relative.startsWith("books/")) {
                    return;
                }
                if (!referencedFilenames.contains(relative)) {
                    orphaned.add(path);
                }
            });
        }
        return orphaned;
    }

    private static List<Path> findOrphanedAudio(Path audioDir, Set<String> bookKeys,
                                                java.util.Map<String, Set<Integer>> chaptersByBookKey,
                                                boolean includeRootCache, boolean legacyOnly) throws IOException {
        List<Path> orphaned = new ArrayList<>();
        if (!Files.isDirectory(audioDir)) {
            return orphaned;
        }

        Path booksRoot = audioDir.resolve("books");
        if (Files.isDirectory(booksRoot)) {
            if (legacyOnly) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(audioDir)) {
                    for (Path entry : entries) {
                        if (entry.getFileName().toString().equals("books")) {
                            continue;
                        }
                        orphaned.add(entry);
                    }
                }
                return orphaned;
            }
            try (DirectoryStream<Path> sourceDirs = Files.newDirectoryStream(booksRoot)) {
                for (Path sourceDir : sourceDirs) {
                    if (!Files.isDirectory(sourceDir)) {
                        if (includeRootCache) {
                            orphaned.add(sourceDir);
                        }
                        continue;
                    }
                    String source = sourceDir.getFileName().toString();
                    try (DirectoryStream<Path> bookDirs = Files.newDirectoryStream(sourceDir)) {
                        for (Path bookDir : bookDirs) {
                            if (!Files.isDirectory(bookDir)) {
                                orphaned.add(bookDir);
                                continue;
                            }
                            String sourceId = bookDir.getFileName().toString();
                            String bookKey = "books/" + source + "/" + sourceId;
                            if (!bookKeys.contains(bookKey)) {
                                orphaned.add(bookDir);
                                continue;
                            }
                            Path audioRoot = bookDir.resolve("audio");
                            if (!Files.isDirectory(audioRoot)) {
                                continue;
                            }
                            try (DirectoryStream<Path> voiceDirs = Files.newDirectoryStream(audioRoot)) {
                                for (Path voiceDir : voiceDirs) {
                                    if (!Files.isDirectory(voiceDir)) {
                                        orphaned.add(voiceDir);
                                        continue;
                                    }
                                    Path chaptersDir = voiceDir.resolve("chapters");
                                    if (!Files.isDirectory(chaptersDir)) {
                                        orphaned.add(voiceDir);
                                        continue;
                                    }
                                    try (DirectoryStream<Path> chapterDirs = Files.newDirectoryStream(chaptersDir)) {
                                        for (Path chapterDir : chapterDirs) {
                                            if (!Files.isDirectory(chapterDir)) {
                                                orphaned.add(chapterDir);
                                                continue;
                                            }
                                            String chapterIndex = chapterDir.getFileName().toString();
                                            Set<Integer> expected = chaptersByBookKey.getOrDefault(bookKey, Set.of());
                                            if (!expected.contains(parseIntSafe(chapterIndex))) {
                                                orphaned.add(chapterDir);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return orphaned;
        }

        try (DirectoryStream<Path> bookDirs = Files.newDirectoryStream(audioDir)) {
            for (Path bookDir : bookDirs) {
                if (Files.isRegularFile(bookDir)) {
                    if (includeRootCache) {
                        orphaned.add(bookDir);
                    }
                    continue;
                }
                if (!Files.isDirectory(bookDir)) {
                    continue;
                }
                if (!legacyOnly) {
                    orphaned.add(bookDir);
                } else {
                    orphaned.add(bookDir);
                }
            }
        }

        return orphaned;
    }

    private static BookKeyData queryBookKeys(Connection connection) throws Exception {
        java.util.Map<String, String> byId = new java.util.HashMap<>();
        Set<String> bookKeys = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select id, source, source_id from books")) {
            while (resultSet.next()) {
                String id = resultSet.getString(1);
                String source = normalizeSegment(resultSet.getString(2));
                String sourceId = normalizeSegment(resultSet.getString(3));
                String key = (!source.isBlank() && !sourceId.isBlank())
                        ? "books/" + source + "/" + sourceId
                        : "books/local/" + normalizeSegment(id);
                byId.put(id, key);
                bookKeys.add(key);
            }
        }
        return new BookKeyData(byId, bookKeys);
    }

    private static BookChapterData queryBookChapters(Connection connection, java.util.Map<String, String> bookKeysById)
            throws Exception {
        java.util.Map<String, Set<Integer>> chaptersByBookKey = new java.util.HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select book_id, chapter_index from chapters")) {
            while (resultSet.next()) {
                String bookId = resultSet.getString(1);
                int chapterIndex = resultSet.getInt(2);
                String bookKey = bookKeysById.get(bookId);
                if (bookKey == null) {
                    continue;
                }
                chaptersByBookKey.computeIfAbsent(bookKey, key -> new HashSet<>()).add(chapterIndex);
            }
        }
        return new BookChapterData(chaptersByBookKey);
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String normalizeSegment(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        return normalized;
    }

    private record BookKeyData(java.util.Map<String, String> bookKeysById, Set<String> bookKeys) {
        private BookKeyData() {
            this(new java.util.HashMap<>(), new HashSet<>());
        }
    }

    private record BookChapterData(java.util.Map<String, Set<Integer>> chaptersByBookKey) {
        private BookChapterData() {
            this(new java.util.HashMap<>());
        }
    }

    private static void report(String label, List<Path> orphaned) {
        System.out.println("Found " + orphaned.size() + " orphaned " + label + ".");
        int shown = 0;
        for (Path path : orphaned) {
            System.out.println("  " + path);
            shown++;
            if (shown >= SAMPLE_LIMIT && orphaned.size() > SAMPLE_LIMIT) {
                System.out.println("  ...");
                break;
            }
        }
    }

    private static int deletePaths(List<Path> paths) {
        int deleted = 0;
        for (Path path : paths) {
            try {
                if (Files.isDirectory(path)) {
                    deleted += deleteDirectory(path);
                } else if (Files.exists(path)) {
                    Files.delete(path);
                    deleted += 1;
                }
            } catch (IOException e) {
                System.err.println("Failed to delete " + path + ": " + e.getMessage());
            }
        }
        return deleted;
    }

    private static int deleteDirectory(Path directory) throws IOException {
        int deleted = 0;
        try (var walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                if (Files.exists(path)) {
                    Files.delete(path);
                    deleted += 1;
                }
            }
        }
        return deleted;
    }

    private static int deleteEmptyDirs(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (var walk = Files.walk(root)) {
            List<Path> dirs = walk.filter(Files::isDirectory)
                .sorted(Comparator.reverseOrder())
                .toList();
            for (Path dir : dirs) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    if (!stream.iterator().hasNext()) {
                        Files.delete(dir);
                        deleted += 1;
                    }
                }
            }
        }
        return deleted;
    }
}
