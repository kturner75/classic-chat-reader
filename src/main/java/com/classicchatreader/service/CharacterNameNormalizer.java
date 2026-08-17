package com.classicchatreader.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared character-name identity rules used by extraction, prefetch, persistence,
 * cache import, and display dedupe. The identity key is what makes "Sally",
 * "sally", and "Sally." the same record.
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
     * Titles are kept so {@code Mrs. Allen} and a different person named
     * {@code Allen} do not share a unique constraint.
     */
    public static String identityKey(String name) {
        return joinTokens(tokenize(name));
    }

    /**
     * Title-stripped form used for last-name-only variant matching
     * ({@code Tilney} vs {@code Mr. Tilney}).
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

    public static boolean isNameVariant(String existingName, String candidateName) {
        if (isSameIdentity(existingName, candidateName)) {
            return true;
        }
        String existingVariant = variantKey(existingName);
        String candidateVariant = variantKey(candidateName);
        if (existingVariant.isBlank() || candidateVariant.isBlank()) {
            return false;
        }
        if (existingVariant.equals(candidateVariant)) {
            return true;
        }
        if (isLastNameOnly(candidateVariant) && lastNameMatches(existingVariant, candidateVariant)) {
            return true;
        }
        return isLastNameOnly(existingVariant) && lastNameMatches(candidateVariant, existingVariant);
    }

    public static boolean isLastNameOnly(String variantKey) {
        return variantKey != null && !variantKey.isBlank() && !variantKey.contains(" ");
    }

    public static boolean lastNameMatches(String variantA, String variantB) {
        if (variantA == null || variantB == null || variantA.isBlank() || variantB.isBlank()) {
            return false;
        }
        String lastA = variantA.substring(variantA.lastIndexOf(' ') + 1);
        String lastB = variantB.substring(variantB.lastIndexOf(' ') + 1);
        return !lastA.isBlank() && lastA.equals(lastB);
    }

    public static String fallbackKey(String id) {
        return "unnamed-" + (id == null || id.isBlank() ? "pending" : id);
    }

    private static List<String> tokenize(String name) {
        if (name == null) {
            return new ArrayList<>();
        }
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z\\s-]", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(cleaned.split(" "))
                .filter(part -> !part.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String joinTokens(List<String> parts) {
        return String.join(" ", parts).trim();
    }
}
