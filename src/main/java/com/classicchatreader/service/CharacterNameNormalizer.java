package com.classicchatreader.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared character-name identity rules used by extraction, prefetch, persistence,
 * cache import, and display dedupe. The identity key is what makes "Sally",
 * "sally", and "Sally." the same record without collapsing distinct people.
 */
public final class CharacterNameNormalizer {

    static final Set<String> NAME_TITLES = Set.of(
            "mr", "mrs", "ms", "miss", "lady", "lord", "sir", "madam", "madame",
            "mme", "mlle", "dr", "doctor", "prof", "professor", "rev", "reverend",
            "capt", "captain", "col", "colonel", "major"
    );

    private CharacterNameNormalizer() {
    }

    /**
     * Stable uniqueness key: case, punctuation, hyphen, and whitespace insensitive.
     * Titles and given names are kept so {@code Mrs. Bennet} and
     * {@code Elizabeth Bennet} do not share a unique constraint.
     */
    public static String identityKey(String name) {
        return joinTokens(tokenize(name));
    }

    /**
     * Title-stripped tokens for generic-role detection only. Not an identity key.
     */
    public static String variantKey(String name) {
        List<String> parts = tokenize(name);
        while (!parts.isEmpty() && NAME_TITLES.contains(parts.get(0))) {
            parts.remove(0);
        }
        return joinTokens(parts);
    }

    public static String displayName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("\\s+", " ");
    }

    public static boolean isSameIdentity(String left, String right) {
        String leftKey = identityKey(left);
        String rightKey = identityKey(right);
        return !leftKey.isBlank() && leftKey.equals(rightKey);
    }

    /**
     * Conservative match used by extraction, prefetch, and upsert.
     * Only exact identity-key equality counts; shared surnames or stripped
     * titles must not collapse distinct people.
     */
    public static boolean isNameVariant(String existingName, String candidateName) {
        return isSameIdentity(existingName, candidateName);
    }

    public static String fallbackKey(String id) {
        return "unnamed-" + (id == null || id.isBlank() ? "pending" : id);
    }

    private static List<String> tokenize(String name) {
        if (name == null) {
            return new ArrayList<>();
        }
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isTokenSeparator(codePoint)) {
                flushToken(parts, token);
            } else if (Character.isLetterOrDigit(codePoint)) {
                token.appendCodePoint(codePoint);
            } else {
                flushToken(parts, token);
            }
        }
        flushToken(parts, token);
        return parts;
    }

    private static boolean isTokenSeparator(int codePoint) {
        return Character.isWhitespace(codePoint)
                || codePoint == '-'
                || codePoint == '\u2010'
                || codePoint == '\u2011'
                || codePoint == '\u2012'
                || codePoint == '\u2013'
                || codePoint == '\u2014';
    }

    private static void flushToken(List<String> parts, StringBuilder token) {
        if (!token.isEmpty()) {
            parts.add(token.toString());
            token.setLength(0);
        }
    }

    private static String joinTokens(List<String> parts) {
        return String.join(" ", parts).trim();
    }
}
