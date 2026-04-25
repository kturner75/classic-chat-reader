package com.classicchatreader.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class AssetMigrationRunner {

    private static final String APPLY_FLAG = "--apply";
    private static final String COPY_FLAG = "--copy";
    private static final String AUDIO_FLAG = "--audio";
    private static final String HELP_FLAG = "--help";

    public static void main(String[] args) {
        List<String> arguments = Arrays.asList(args);
        if (arguments.contains(HELP_FLAG)) {
            printUsage();
            return;
        }

        boolean apply = arguments.contains(APPLY_FLAG);
        boolean copyOnly = arguments.contains(COPY_FLAG);
        boolean includeAudio = arguments.contains(AUDIO_FLAG);

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
            MigrationStats stats = new MigrationStats();
            migrateIllustrations(connection, illustrationDir, apply, copyOnly, stats);
            migratePortraits(connection, portraitDir, apply, copyOnly, stats);
            if (includeAudio) {
                migrateAudio(connection, audioDir, apply, copyOnly, stats);
            } else {
                System.out.println("Audio migration skipped (add --audio to enable).");
            }

            stats.printSummary(apply, copyOnly);
        } catch (Exception e) {
            System.err.println("Asset migration failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("Usage: AssetMigrationRunner [--apply] [--copy] [--audio]");
        System.out.println("  --apply  Execute migration (default is dry run).");
        System.out.println("  --copy   Copy instead of move when applying.");
        System.out.println("  --audio  Migrate TTS audio cache to stable keys.");
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = AssetMigrationRunner.class.getClassLoader().getResourceAsStream("application.properties")) {
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

    private static void migrateIllustrations(Connection connection, Path illustrationDir, boolean apply,
                                             boolean copyOnly, MigrationStats stats) throws Exception {
        List<IllustrationRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                 select i.id, i.image_filename, c.chapter_index, b.id, b.source, b.source_id
                 from illustrations i
                 join chapters c on i.chapter_id = c.id
                 join books b on c.book_id = b.id
                 where i.image_filename is not null
                 """)) {
            while (rs.next()) {
                rows.add(new IllustrationRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6)
                ));
            }
        }

        if (rows.isEmpty()) {
            System.out.println("No illustration records to migrate.");
            return;
        }

        try (PreparedStatement update = connection.prepareStatement(
                "update illustrations set image_filename = ? where id = ?")) {
            for (IllustrationRow row : rows) {
                String current = row.filename();
                if (isStableKey(current)) {
                    stats.illustrationsSkipped++;
                    continue;
                }
                String bookKey = buildBookKey(row.bookId(), row.source(), row.sourceId());
                String targetKey = bookKey + "/illustrations/chapters/" + row.chapterIndex() + ".png";
                if (targetKey.equals(current)) {
                    stats.illustrationsSkipped++;
                    continue;
                }
                Path sourcePath = illustrationDir.resolve(current);
                Path targetPath = illustrationDir.resolve(targetKey);
                if (!Files.exists(sourcePath)) {
                    stats.illustrationsMissing++;
                    continue;
                }
                if (apply) {
                    Files.createDirectories(targetPath.getParent());
                    if (copyOnly) {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    update.setString(1, targetKey.replace('\\', '/'));
                    update.setString(2, row.id());
                    update.executeUpdate();
                    stats.illustrationsMigrated++;
                } else {
                    stats.illustrationsPlanned++;
                }
            }
        }
    }

    private static void migratePortraits(Connection connection, Path portraitDir, boolean apply,
                                         boolean copyOnly, MigrationStats stats) throws Exception {
        List<PortraitRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                 select c.id, c.portrait_filename, c.name, c.first_paragraph_index, ch.chapter_index,
                        b.id, b.source, b.source_id
                 from characters c
                 join chapters ch on c.first_chapter_id = ch.id
                 join books b on c.book_id = b.id
                 where c.portrait_filename is not null
                 """)) {
            while (rs.next()) {
                rows.add(new PortraitRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getInt(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8)
                ));
            }
        }

        if (rows.isEmpty()) {
            System.out.println("No portrait records to migrate.");
            return;
        }

        Map<String, Integer> nameCounts = new HashMap<>();
        for (PortraitRow row : rows) {
            String slug = normalizeSegment(row.name());
            if (slug.isBlank()) {
                slug = "character";
            }
            String key = row.bookId() + "|" + slug;
            nameCounts.put(key, nameCounts.getOrDefault(key, 0) + 1);
        }

        try (PreparedStatement update = connection.prepareStatement(
                "update characters set portrait_filename = ? where id = ?")) {
            for (PortraitRow row : rows) {
                String current = row.filename();
                if (isStableKey(current)) {
                    stats.portraitsSkipped++;
                    continue;
                }
                String slug = normalizeSegment(row.name());
                if (slug.isBlank()) {
                    slug = "character";
                }
                String key = row.bookId() + "|" + slug;
                if (nameCounts.getOrDefault(key, 0) > 1) {
                    slug = slug + "-" + row.chapterIndex() + "-" + row.firstParagraphIndex();
                }
                String bookKey = buildBookKey(row.bookId(), row.source(), row.sourceId());
                String targetKey = bookKey + "/portraits/characters/" + slug + ".png";
                if (targetKey.length() > 255) {
                    int maxSlugLength = Math.max(16, 255 - (bookKey + "/portraits/characters/").length() - ".png".length());
                    slug = slug.length() > maxSlugLength ? slug.substring(0, maxSlugLength).replaceAll("-+$", "") : slug;
                    targetKey = bookKey + "/portraits/characters/" + slug + ".png";
                }
                if (targetKey.equals(current)) {
                    stats.portraitsSkipped++;
                    continue;
                }
                Path sourcePath = portraitDir.resolve(current);
                Path targetPath = portraitDir.resolve(targetKey);
                if (!Files.exists(sourcePath)) {
                    stats.portraitsMissing++;
                    continue;
                }
                if (apply) {
                    Files.createDirectories(targetPath.getParent());
                    if (copyOnly) {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    update.setString(1, targetKey.replace('\\', '/'));
                    update.setString(2, row.id());
                    update.executeUpdate();
                    stats.portraitsMigrated++;
                } else {
                    stats.portraitsPlanned++;
                }
            }
        }
    }

    private static void migrateAudio(Connection connection, Path audioDir, boolean apply,
                                     boolean copyOnly, MigrationStats stats) throws Exception {
        Map<String, BookKey> bookKeysById = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select id, source, source_id from books")) {
            while (rs.next()) {
                String bookId = rs.getString(1);
                String source = rs.getString(2);
                String sourceId = rs.getString(3);
                bookKeysById.put(bookId, new BookKey(bookId, source, sourceId));
            }
        }

        Map<String, Integer> chapterIndexById = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select id, chapter_index from chapters")) {
            while (rs.next()) {
                chapterIndexById.put(rs.getString(1), rs.getInt(2));
            }
        }

        if (!Files.isDirectory(audioDir)) {
            System.out.println("Audio cache directory not found, skipping.");
            return;
        }

        try (DirectoryStream<Path> bookDirs = Files.newDirectoryStream(audioDir)) {
            for (Path bookDir : bookDirs) {
                if (!Files.isDirectory(bookDir)) {
                    continue;
                }
                String bookId = bookDir.getFileName().toString();
                if ("books".equals(bookId)) {
                    continue;
                }
                BookKey bookKey = bookKeysById.get(bookId);
                if (bookKey == null) {
                    stats.audioMissingBooks++;
                    continue;
                }
                String stableBookKey = buildBookKey(bookKey.bookId(), bookKey.source(), bookKey.sourceId());
                try (DirectoryStream<Path> voiceDirs = Files.newDirectoryStream(bookDir)) {
                    for (Path voiceDir : voiceDirs) {
                        if (!Files.isDirectory(voiceDir)) {
                            continue;
                        }
                        String voice = voiceDir.getFileName().toString();
                        try (DirectoryStream<Path> chapterDirs = Files.newDirectoryStream(voiceDir)) {
                            for (Path chapterDir : chapterDirs) {
                                if (!Files.isDirectory(chapterDir)) {
                                    continue;
                                }
                                String chapterId = chapterDir.getFileName().toString();
                                Integer chapterIndex = chapterIndexById.get(chapterId);
                                if (chapterIndex == null) {
                                    stats.audioMissingChapters++;
                                    continue;
                                }
                                Path targetRoot = audioDir.resolve(stableBookKey)
                                        .resolve("audio")
                                        .resolve(voice)
                                        .resolve("chapters")
                                        .resolve(String.valueOf(chapterIndex));
                                Files.createDirectories(targetRoot);
                                try (DirectoryStream<Path> audioFiles = Files.newDirectoryStream(chapterDir)) {
                                    for (Path audioFile : audioFiles) {
                                        if (!Files.isRegularFile(audioFile)) {
                                            continue;
                                        }
                                        Path targetFile = targetRoot.resolve(audioFile.getFileName().toString());
                                        if (apply) {
                                            if (copyOnly) {
                                                Files.copy(audioFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                                            } else {
                                                Files.move(audioFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                                            }
                                            stats.audioMigrated++;
                                        } else {
                                            stats.audioPlanned++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isStableKey(String filename) {
        if (filename == null) {
            return false;
        }
        return filename.startsWith("books/");
    }

    private static String buildBookKey(String bookId, String source, String sourceId) {
        String normalizedSource = normalizeSegment(source);
        String normalizedSourceId = normalizeSegment(sourceId);
        if (!normalizedSource.isBlank() && !normalizedSourceId.isBlank()) {
            return "books/" + normalizedSource + "/" + normalizedSourceId;
        }
        return "books/local/" + normalizeSegment(bookId);
    }

    private static String normalizeSegment(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
            normalized = normalized.replaceAll("-+$", "");
        }
        return normalized;
    }

    private record IllustrationRow(String id, String filename, int chapterIndex, String bookId,
                                   String source, String sourceId) {}

    private record PortraitRow(String id, String filename, String name, int firstParagraphIndex,
                               int chapterIndex, String bookId, String source, String sourceId) {}

    private record BookKey(String bookId, String source, String sourceId) {}

    private static class MigrationStats {
        int illustrationsPlanned;
        int illustrationsMigrated;
        int illustrationsSkipped;
        int illustrationsMissing;
        int portraitsPlanned;
        int portraitsMigrated;
        int portraitsSkipped;
        int portraitsMissing;
        int audioPlanned;
        int audioMigrated;
        int audioMissingBooks;
        int audioMissingChapters;

        void printSummary(boolean apply, boolean copyOnly) {
            String mode = apply ? (copyOnly ? "apply (copy)" : "apply (move)") : "dry run";
            System.out.println("Asset migration summary (" + mode + "):");
            System.out.println("  Illustrations: planned=" + illustrationsPlanned
                    + " migrated=" + illustrationsMigrated
                    + " skipped=" + illustrationsSkipped
                    + " missing=" + illustrationsMissing);
            System.out.println("  Portraits: planned=" + portraitsPlanned
                    + " migrated=" + portraitsMigrated
                    + " skipped=" + portraitsSkipped
                    + " missing=" + portraitsMissing);
            if (audioPlanned > 0 || audioMigrated > 0 || audioMissingBooks > 0 || audioMissingChapters > 0) {
                System.out.println("  Audio: planned=" + audioPlanned
                        + " migrated=" + audioMigrated
                        + " missingBooks=" + audioMissingBooks
                        + " missingChapters=" + audioMissingChapters);
            }
        }
    }
}
