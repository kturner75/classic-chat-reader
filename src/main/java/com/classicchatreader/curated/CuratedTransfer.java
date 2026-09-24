package com.classicchatreader.curated;

import com.classicchatreader.service.CuratedBookStore;
import com.classicchatreader.service.CuratedCatalogService;
import com.classicchatreader.transfer.SerializableTransaction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * One title's curated catalog membership (BL-072.4), so Studio can list or unlist a book on production.
 * Touches only its {@code curated_books} row: never books, art, characters, files or Spaces.
 */
public final class CuratedTransfer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private CuratedTransfer() {}

    public record Membership(String title, String author, int popularity, List<String> subjects,
                             List<String> bookshelves, List<String> aliases, String status) {}
    /** {@code membership} is null when the destination has no row for the book. */
    public record Snapshot(String source, String sourceId, String revision, Membership membership) {}
    public record Plan(String source, String sourceId, String expectedRevision, Membership membership, boolean confirm) {}
    public static final class StalePlan extends IllegalStateException {
        public StalePlan(String message) { super(message); }
    }

    public static Snapshot exportMembership(Connection c, String source, String sourceId) throws Exception {
        return SerializableTransaction.run(c, () -> snapshot(c, source, sourceId, false));
    }

    /** Inserts or replaces the book's row with the plan's membership, then reads it back. */
    public static Snapshot apply(Connection c, Plan plan) throws Exception {
        if (plan == null || !plan.confirm()) throw new IllegalArgumentException("Explicit confirmation is required");
        Membership wanted = normalize(plan.membership());
        return SerializableTransaction.run(c, () -> {
            Snapshot before = snapshot(c, plan.source(), plan.sourceId(), true);
            if (!Objects.equals(before.revision(), plan.expectedRevision()))
                throw new StalePlan("Curated membership changed. Refresh the comparison before confirming.");
            Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
            try (PreparedStatement s = c.prepareStatement(before.membership() == null ? """
                    INSERT INTO curated_books (title, author, popularity, subjects_json, bookshelves_json, aliases_json,
                      status, updated_at, created_at, id, source, source_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""" : """
                    UPDATE curated_books SET title = ?, author = ?, popularity = ?, subjects_json = ?, bookshelves_json = ?,
                      aliases_json = ?, status = ?, updated_at = ? WHERE source = ? AND source_id = ?""")) {
                s.setString(1, wanted.title()); s.setString(2, wanted.author()); s.setInt(3, wanted.popularity());
                s.setString(4, JSON.writeValueAsString(wanted.subjects()));
                s.setString(5, JSON.writeValueAsString(wanted.bookshelves()));
                s.setString(6, JSON.writeValueAsString(wanted.aliases()));
                s.setString(7, wanted.status()); s.setTimestamp(8, now);
                if (before.membership() == null) {
                    s.setTimestamp(9, now); s.setString(10, plan.source() + ":" + plan.sourceId());
                    s.setString(11, plan.source()); s.setString(12, plan.sourceId());
                } else {
                    s.setString(9, plan.source()); s.setString(10, plan.sourceId());
                }
                if (s.executeUpdate() != 1) throw new IllegalStateException("Curated membership write did not match exactly one row");
            }
            Snapshot after = snapshot(c, plan.source(), plan.sourceId(), false);
            if (!wanted.equals(after.membership())) throw new IllegalStateException("Curated membership did not match the plan");
            return after;
        });
    }

    private static Snapshot snapshot(Connection c, String source, String sourceId, boolean lock) throws Exception {
        requireGutenberg(source, sourceId);
        Membership membership = null;
        try (PreparedStatement s = c.prepareStatement("""
                SELECT title, author, popularity, subjects_json, bookshelves_json, aliases_json, status
                FROM curated_books WHERE source = ? AND source_id = ?""" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, source); s.setString(2, sourceId);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    membership = new Membership(r.getString(1), r.getString(2), r.getInt(3), list(r.getString(4)),
                            list(r.getString(5)), list(r.getString(6)), r.getString(7));
                }
            }
        }
        byte[] payload = JSON.writeValueAsBytes(Arrays.asList(source, sourceId, membership));
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        return new Snapshot(source, sourceId, revision, membership);
    }

    /** The same rules as the local membership API, so a title listed locally can always ship. */
    static Membership normalize(Membership membership) {
        if (membership == null) throw new IllegalArgumentException("Membership is required");
        if (membership.popularity() < 0) throw new IllegalArgumentException("popularity must not be negative");
        if (!CuratedBookStore.STATUS_ACTIVE.equals(membership.status()) && !CuratedBookStore.STATUS_INACTIVE.equals(membership.status()))
            throw new IllegalArgumentException("status must be \"active\" or \"inactive\"");
        return new Membership(
                CuratedCatalogService.normalizeText("title", membership.title()),
                CuratedCatalogService.normalizeText("author", membership.author()),
                membership.popularity(),
                CuratedCatalogService.normalizeList("subjects", membership.subjects()),
                CuratedCatalogService.normalizeList("bookshelves", membership.bookshelves()),
                CuratedCatalogService.normalizeList("aliases", membership.aliases()),
                membership.status());
    }

    private static void requireGutenberg(String source, String sourceId) {
        if (!CuratedBookStore.SOURCE_GUTENBERG.equals(source)) throw new IllegalArgumentException("source must be \"gutenberg\"");
        // Parse as the catalog does: a row whose id is not a positive int would break every catalog read.
        int id;
        try {
            id = sourceId == null || !sourceId.matches("[1-9][0-9]*") ? 0 : Integer.parseInt(sourceId);
        } catch (NumberFormatException e) {
            id = 0;
        }
        if (id <= 0) throw new IllegalArgumentException("sourceId must be a Gutenberg number");
    }

    private static List<String> list(String json) throws Exception {
        return json == null || json.isBlank() ? List.of() : JSON.readValue(json, STRING_LIST);
    }
}
