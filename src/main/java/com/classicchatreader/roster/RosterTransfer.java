package com.classicchatreader.roster;

import com.classicchatreader.transfer.SerializableTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Roster-only transaction shared by the local operator API and production CLI. */
public final class RosterTransfer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RosterTransfer() {}

    public record Row(String id, String name, String characterType, Integer firstChapterIndex,
                      Integer firstParagraphIndex, String description) {}
    public record LiveRow(Row character, String portraitFilename, String callVoice,
                          String status, long conversations, long messages) {}
    public record Chapter(int index, List<Integer> paragraphs) {}
    public record Snapshot(String source, String sourceId, String revision,
                           List<LiveRow> characters, List<Chapter> chapters) {}
    public record Plan(String source, String sourceId, String expectedRevision,
                       List<Row> rows, List<String> removeIds, boolean confirm) {}
    public static final class StalePlan extends IllegalStateException {
        public StalePlan(String message) { super(message); }
    }

    public static Snapshot exportRoster(Connection c, String source, String sourceId) throws Exception {
        return SerializableTransaction.run(c, () -> snapshot(c, source, sourceId, bookId(c, source, sourceId, false)));
    }

    private static String bookId(Connection c, String source, String sourceId, boolean lock) throws SQLException {
        if (source == null || source.isBlank() || sourceId == null || sourceId.isBlank())
            throw new IllegalArgumentException("Book source and sourceId are required");
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM books WHERE source = ? AND source_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, source); s.setString(2, sourceId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Destination book was not found");
                String id = r.getString(1);
                if (r.next()) throw new IllegalArgumentException("Destination book is ambiguous");
                return id;
            }
        }
    }

    private static Snapshot snapshot(Connection c, String source, String sourceId, String bookId) throws Exception {
        List<LiveRow> rows = new ArrayList<>();
        List<List<String>> revisionRows = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT ch.*, fc.chapter_index FROM characters ch
                JOIN chapters fc ON ch.first_chapter_id = fc.id
                WHERE ch.book_id = ? ORDER BY ch.id
                """)) {
            s.setString(1, bookId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    List<String> raw = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) raw.add(r.getString(i));
                    revisionRows.add(raw);
                    String id = r.getString("id");
                    long conversations = count(c, "SELECT COUNT(*) FROM character_chat_conversations WHERE character_id = ?", id);
                    long messages = count(c, "SELECT COUNT(*) FROM character_chat_messages m JOIN character_chat_conversations t ON m.conversation_id = t.id WHERE t.character_id = ?", id);
                    rows.add(new LiveRow(new Row(id, r.getString("name"), r.getString("character_type"),
                            r.getInt("chapter_index"), r.getInt("first_paragraph_index"), r.getString("description")),
                            r.getString("portrait_filename"), r.getString("call_voice"), r.getString("status"), conversations, messages));
                }
            }
        }
        List<Chapter> chapters = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT id, chapter_index FROM chapters WHERE book_id = ? ORDER BY chapter_index")) {
            s.setString(1, bookId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    List<Integer> paragraphs = new ArrayList<>();
                    try (PreparedStatement p = c.prepareStatement("SELECT paragraph_index FROM paragraphs WHERE chapter_id = ? ORDER BY paragraph_index")) {
                        p.setString(1, r.getString("id"));
                        try (ResultSet pr = p.executeQuery()) { while (pr.next()) paragraphs.add(pr.getInt(1)); }
                    }
                    chapters.add(new Chapter(r.getInt("chapter_index"), paragraphs));
                }
            }
        }
        // Chat counts stay on the snapshot for Studio; they are advisory and must
        // not stale a confirm when only conversations changed.
        byte[] payload = JSON.writeValueAsBytes(Arrays.asList(source, sourceId, bookId, revisionRows, chapters));
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        return new Snapshot(source, sourceId, revision, rows, chapters);
    }

    private static long count(Connection c, String sql, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    public static Snapshot apply(Connection c, Plan plan) throws Exception {
        if (plan == null || !plan.confirm()) throw new IllegalArgumentException("Explicit confirmation is required");
        return SerializableTransaction.run(c, () -> {
            String bookId = bookId(c, plan.source(), plan.sourceId(), true);
            // Lock existing character rows as well as the book before comparing the revision.
            try (PreparedStatement s = c.prepareStatement("SELECT id FROM characters WHERE book_id = ? FOR UPDATE")) {
                s.setString(1, bookId);
                try (ResultSet r = s.executeQuery()) { while (r.next()) { /* acquire all row locks */ } }
            }
            Snapshot before = snapshot(c, plan.source(), plan.sourceId(), bookId);
            if (!Objects.equals(before.revision(), plan.expectedRevision())) throw new StalePlan("Roster changed. Refresh the preview before confirming.");
            if (plan.rows() == null || plan.rows().isEmpty()) throw new IllegalArgumentException("Empty roster replacement is not allowed");
            if (plan.removeIds() == null) throw new IllegalArgumentException("Explicit removals are required");
            Map<String, LiveRow> existing = new HashMap<>();
            for (LiveRow row : before.characters()) {
                if ("GENERATING".equals(row.status()) || "PENDING".equals(row.status()))
                    throw new StalePlan("Character generation is in progress. Wait for it to finish.");
                existing.put(row.character().id(), row);
            }
            Set<String> retained = new HashSet<>();
            Set<String> names = new HashSet<>();
            Map<Integer, String> chapterIds = new HashMap<>();
            try (PreparedStatement s = c.prepareStatement("SELECT id, chapter_index FROM chapters WHERE book_id = ?")) {
                s.setString(1, bookId);
                try (ResultSet r = s.executeQuery()) { while (r.next()) {
                    if (chapterIds.put(r.getInt(2), r.getString(1)) != null) throw new IllegalArgumentException("Ambiguous chapter index");
                } }
            }
            for (Row row : plan.rows()) {
                if (row == null || row.name() == null || row.name().isBlank() || row.name().length() > 255 || !row.name().equals(row.name().trim()) || !names.add(row.name()))
                    throw new IllegalArgumentException("Character names must be nonblank, trimmed, unique, and at most 255 characters");
                if (!Set.of("PRIMARY", "SECONDARY").contains(Objects.toString(row.characterType(), ""))) throw new IllegalArgumentException("Invalid character type");
                if (row.description() != null && row.description().length() > 2000) throw new IllegalArgumentException("Description is too long");
                if (row.id() != null && (!existing.containsKey(row.id()) || !retained.add(row.id()))) throw new IllegalArgumentException("Character ID is missing, duplicated, or belongs to another book");
                boolean placement = before.chapters().stream().anyMatch(ch -> Objects.equals(ch.index(), row.firstChapterIndex()) && ch.paragraphs().contains(row.firstParagraphIndex()));
                if (!placement) throw new IllegalArgumentException("Character placement must refer to a paragraph in this book");
            }
            Set<String> removed = new HashSet<>(plan.removeIds());
            Set<String> expectedRemoved = new HashSet<>(existing.keySet()); expectedRemoved.removeAll(retained);
            if (removed.size() != plan.removeIds().size() || !removed.equals(expectedRemoved)) throw new IllegalArgumentException("Explicit removals must exactly match the omitted destination characters");
            for (String id : removed) execute(c, "DELETE FROM characters WHERE id = ? AND book_id = ?", id, bookId);
            // Temporary names allow swaps without breaking the unique (book_id, name) constraint.
            for (String id : retained) execute(c, "UPDATE characters SET name = ? WHERE id = ?", "__studio_" + UUID.randomUUID(), id);
            for (Row row : plan.rows()) {
                if (row.id() == null) {
                    execute(c, """
                            INSERT INTO characters (id, book_id, name, character_type, description,
                              first_chapter_id, first_paragraph_index, status, created_at, retry_count)
                            VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', CURRENT_TIMESTAMP, 0)
                            """, UUID.randomUUID().toString(), bookId, row.name(), row.characterType(), row.description(), chapterIds.get(row.firstChapterIndex()), row.firstParagraphIndex());
                } else {
                    // Settle retained FAILED/lease state so confirm cannot latch prefetch
                    // on a stuck row. New inserts are already metadata-only COMPLETED.
                    execute(c, """
                            UPDATE characters SET name = ?, character_type = ?, description = ?, first_chapter_id = ?, first_paragraph_index = ?,
                              call_voice = CASE WHEN ? = 'SECONDARY' THEN NULL ELSE call_voice END,
                              call_voice_provider = CASE WHEN ? = 'SECONDARY' THEN NULL ELSE call_voice_provider END,
                              status = 'COMPLETED', error_message = NULL, lease_expires_at = NULL,
                              lease_owner = NULL, next_retry_at = NULL, retry_count = 0 WHERE id = ?
                            """, row.name(), row.characterType(), row.description(), chapterIds.get(row.firstChapterIndex()), row.firstParagraphIndex(), row.characterType(), row.characterType(), row.id());
                }
            }
            Snapshot after = snapshot(c, plan.source(), plan.sourceId(), bookId);
            verifyApplied(plan, after, removed);
            if (after.characters().stream().allMatch(row -> "COMPLETED".equals(row.status()))) {
                execute(c, "UPDATE books SET character_prefetch_completed = TRUE WHERE id = ?", bookId);
            } else {
                execute(c, "UPDATE books SET character_prefetch_completed = FALSE WHERE id = ?", bookId);
            }
            return after;
        });
    }

    private static void verifyApplied(Plan plan, Snapshot after, Set<String> removed) {
        if (after.characters().size() != plan.rows().size()) {
            throw new IllegalStateException("Roster replacement did not match the plan");
        }
        Map<String, LiveRow> byId = new HashMap<>();
        Map<String, LiveRow> byName = new HashMap<>();
        for (LiveRow live : after.characters()) {
            byId.put(live.character().id(), live);
            byName.put(live.character().name(), live);
        }
        for (String id : removed) {
            if (byId.containsKey(id)) throw new IllegalStateException("Removed character is still present");
        }
        for (Row row : plan.rows()) {
            LiveRow live = row.id() != null ? byId.get(row.id()) : byName.get(row.name());
            if (live == null || !sameRosterRow(row, live.character())) {
                throw new IllegalStateException("Roster replacement did not match the plan");
            }
        }
    }

    private static boolean sameRosterRow(Row expected, Row actual) {
        return expected.name().equals(actual.name())
                && expected.characterType().equals(actual.characterType())
                && Objects.equals(expected.firstChapterIndex(), actual.firstChapterIndex())
                && Objects.equals(expected.firstParagraphIndex(), actual.firstParagraphIndex())
                && Objects.equals(expected.description(), actual.description())
                && (expected.id() == null || expected.id().equals(actual.id()));
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }
}
