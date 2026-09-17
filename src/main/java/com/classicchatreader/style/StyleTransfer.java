package com.classicchatreader.style;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Book art style only: never touches images, generation queues, characters, or Spaces. */
public final class StyleTransfer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private StyleTransfer() {}

    /** Column limits from the books table (V1, V29). */
    static final int STYLE_MAX = 255, PREFIX_MAX = 1000, SETTING_MAX = 1000, REASONING_MAX = 2000,
            COVER_SUBJECT_MAX = 32, COVER_FOCUS_MAX = 500;

    public record Style(String style, String promptPrefix, String setting, String reasoning,
                        String coverSubject, String coverFocus) {}
    public record Snapshot(String source, String sourceId, String revision, Style style) {}
    public record Plan(String source, String sourceId, String expectedRevision, Style style, boolean confirm) {}
    public static final class StalePlan extends IllegalStateException {
        public StalePlan(String message) { super(message); }
    }

    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private static <T> T transaction(Connection c, Work<T> work) throws Exception {
        if (!c.getAutoCommit()) throw new IllegalArgumentException("A dedicated connection is required");
        int isolation = c.getTransactionIsolation();
        c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        c.setAutoCommit(false);
        try {
            T result = work.run();
            c.commit();
            return result;
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
            c.setTransactionIsolation(isolation);
        }
    }

    public static Snapshot exportStyle(Connection c, String source, String sourceId) throws Exception {
        return transaction(c, () -> snapshot(c, source, sourceId, false));
    }

    private static Snapshot snapshot(Connection c, String source, String sourceId, boolean lock) throws Exception {
        if (source == null || source.isBlank() || sourceId == null || sourceId.isBlank())
            throw new IllegalArgumentException("Book source and sourceId are required");
        try (PreparedStatement s = c.prepareStatement("""
                SELECT id, illustration_style, illustration_prompt_prefix, illustration_setting,
                  illustration_style_reasoning, illustration_cover_subject, illustration_cover_focus
                FROM books WHERE source = ? AND source_id = ?""" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, source); s.setString(2, sourceId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Destination book was not found");
                String bookId = r.getString(1);
                Style style = new Style(r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7));
                if (r.next()) throw new IllegalArgumentException("Destination book is ambiguous");
                byte[] payload = JSON.writeValueAsBytes(Arrays.asList(source, sourceId, bookId, style));
                String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
                return new Snapshot(source, sourceId, revision, style);
            }
        }
    }

    /** Replaces all six style fields. Blank optional fields are stored as NULL. */
    public static Snapshot apply(Connection c, Plan plan) throws Exception {
        if (plan == null || !plan.confirm()) throw new IllegalArgumentException("Explicit confirmation is required");
        Style wanted = normalize(plan.style());
        return transaction(c, () -> {
            Snapshot before = snapshot(c, plan.source(), plan.sourceId(), true);
            if (!Objects.equals(before.revision(), plan.expectedRevision())) throw new StalePlan("Book style changed. Refresh the preview before confirming.");
            try (PreparedStatement s = c.prepareStatement("""
                    UPDATE books SET illustration_style = ?, illustration_prompt_prefix = ?, illustration_setting = ?,
                      illustration_style_reasoning = ?, illustration_cover_subject = ?, illustration_cover_focus = ?
                    WHERE source = ? AND source_id = ?""")) {
                s.setString(1, wanted.style()); s.setString(2, wanted.promptPrefix()); s.setString(3, wanted.setting());
                s.setString(4, wanted.reasoning()); s.setString(5, wanted.coverSubject()); s.setString(6, wanted.coverFocus());
                s.setString(7, plan.source()); s.setString(8, plan.sourceId());
                if (s.executeUpdate() != 1) throw new IllegalStateException("Book style update did not match exactly one book");
            }
            Snapshot after = snapshot(c, plan.source(), plan.sourceId(), false);
            if (!wanted.equals(after.style())) throw new IllegalStateException("Book style did not match the plan");
            return after;
        });
    }

    static Style normalize(Style style) {
        if (style == null) throw new IllegalArgumentException("Style is required");
        Style result = new Style(field("style", style.style(), STYLE_MAX), field("promptPrefix", style.promptPrefix(), PREFIX_MAX),
                field("setting", style.setting(), SETTING_MAX), field("reasoning", style.reasoning(), REASONING_MAX),
                field("coverSubject", style.coverSubject(), COVER_SUBJECT_MAX), field("coverFocus", style.coverFocus(), COVER_FOCUS_MAX));
        if (result.style() == null || result.promptPrefix() == null) throw new IllegalArgumentException("Style name and prompt prefix must not be blank");
        return result;
    }

    private static String field(String name, String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        // Reject rather than clip: a silently shortened production style would not match the reviewed draft.
        if (trimmed.length() > max) throw new IllegalArgumentException(name + " must be at most " + max + " characters");
        return trimmed;
    }
}
