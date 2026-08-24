package com.classicchatreader.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared roster gate for prefetch (PRIMARY) and chapter extraction (SECONDARY).
 *
 * <p>Named people only: reject mass nouns, animals, objects, places, collectives,
 * celestial bodies, servant-class roles, and LLM leftover / glitch names.
 */
public final class CharacterRosterNameFilter {

    private static final Set<String> NAME_TITLES = Set.of(
            "mr", "mrs", "ms", "miss", "lady", "lord", "sir", "madam", "madame",
            "mme", "mlle", "dr", "doctor", "prof", "professor", "rev", "reverend",
            "capt", "captain", "col", "colonel", "major"
    );
    private static final Set<String> GENERIC_DESCRIPTORS = Set.of(
            "man", "woman", "boy", "girl", "child", "stranger", "servant", "maid",
            "butler", "sailor", "soldier", "officer", "guard", "driver", "porter",
            "passerby", "gentleman", "lady", "visitor", "neighbor"
    );
    private static final Set<String> GENERIC_DESCRIPTOR_TOKENS = Set.of(
            "man", "men", "woman", "women", "boy", "boys", "girl", "girls", "child", "children",
            "stranger", "strangers", "servant", "servants", "maid", "maids", "butler", "butlers",
            "sailor", "sailors", "soldier", "soldiers", "officer", "officers", "guard", "guards",
            "driver", "drivers", "porter", "porters", "passerby", "passersby", "gentleman",
            "gentlemen", "lady", "ladies", "visitor", "visitors", "neighbor", "neighbors",
            "people", "folk"
    );
    private static final Set<String> ARTICLE_TOKENS = Set.of(
            "the", "a", "an", "some", "another", "any"
    );
    /**
     * Exact names (after normalize) that are not people: Moon/Mule class, mass nouns,
     * animals, objects, generic places, collectives.
     */
    private static final Set<String> NON_PERSON_NAMES = Set.of(
            "moon", "sun", "star", "stars", "earth", "sky", "heavens", "heaven",
            "planet", "comet", "aurora",
            "mule", "horse", "dog", "cat", "bird", "beast", "wolf", "lion", "snake",
            "bee", "bees", "cattle", "sheep", "goat", "ox", "rat", "mouse",
            "crowd", "crowds", "mob", "guests", "audience", "villagers", "peasants",
            "public", "swarm", "flock", "herd", "crew", "chorus",
            "ship", "boat", "carriage", "letter", "book", "house", "castle",
            "door", "window", "lamp", "table", "sword", "gun",
            "forest", "mountain", "ocean", "sea", "river", "island", "arctic",
            "desert", "village", "city", "town", "country"
    );
    private static final Set<String> LLM_LEFTOVER_NAMES = Set.of(
            "character name", "unknown", "n a", "none", "tbd", "placeholder"
    );
    private static final Pattern GLITCH_LEFTOVER = Pattern.compile(
            "(?i)\\(\\s*(again|continued|duplicate|same|revised|repeat)\\s*\\)");

    private CharacterRosterNameFilter() {
    }

    public static boolean isClearlyNamed(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String trimmed = name.trim();
        if (GLITCH_LEFTOVER.matcher(trimmed).find()) {
            return false;
        }
        if (isGenericDescriptorPhrase(trimmed)) {
            return false;
        }
        String normalized = normalizeName(trimmed);
        if (normalized.isBlank()) {
            return false;
        }
        if (LLM_LEFTOVER_NAMES.contains(normalized)) {
            return false;
        }
        if (NON_PERSON_NAMES.contains(normalized)) {
            return false;
        }
        if (normalized.split(" ").length == 1 && GENERIC_DESCRIPTORS.contains(normalized)) {
            return false;
        }
        return true;
    }

    private static boolean isGenericDescriptorPhrase(String name) {
        String normalized = normalizeName(name);
        if (normalized.isBlank()) {
            return true;
        }
        String[] normalizedTokens = normalized.split(" ");
        String lastToken = normalizedTokens[normalizedTokens.length - 1];
        if (!GENERIC_DESCRIPTOR_TOKENS.contains(lastToken)) {
            return false;
        }

        String[] originalTokens = name.trim().split("\\s+");
        int uppercaseTokens = 0;
        int uppercaseNonGenericTokens = 0;
        boolean firstTokenHasUppercase = false;
        for (int i = 0; i < originalTokens.length; i++) {
            String token = originalTokens[i];
            boolean hasUppercase = token.chars().anyMatch(Character::isUpperCase);
            if (!hasUppercase) {
                continue;
            }
            uppercaseTokens++;
            if (i == 0) {
                firstTokenHasUppercase = true;
            }
            String normalizedToken = normalizeName(token);
            if (!normalizedToken.isBlank() && !GENERIC_DESCRIPTOR_TOKENS.contains(normalizedToken)) {
                uppercaseNonGenericTokens++;
            }
        }

        if (uppercaseNonGenericTokens >= 2) {
            return false;
        }
        if (uppercaseNonGenericTokens == 1 && !(uppercaseTokens == 1 && firstTokenHasUppercase)) {
            return false;
        }

        return true;
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.toLowerCase()
                .replaceAll("[^a-z\\s-]", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        List<String> parts = Arrays.stream(cleaned.split(" "))
                .filter(part -> !part.isBlank())
                .collect(Collectors.toList());
        while (!parts.isEmpty() && (NAME_TITLES.contains(parts.get(0)) || ARTICLE_TOKENS.contains(parts.get(0)))) {
            parts.remove(0);
        }
        return String.join(" ", parts).trim();
    }

    /**
     * True when {@code text} contains the roster name as a whole-word phrase.
     * Used to locate first appearance of names we already trust.
     */
    public static boolean appearsInText(String name, String text) {
        if (name == null || name.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        return appearancePattern(name).matcher(text).find();
    }

    static Pattern appearancePattern(String name) {
        String trimmed = name.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return Pattern.compile("\\b" + Pattern.quote(trimmed) + "\\b", Pattern.CASE_INSENSITIVE);
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile("\\b" + regex + "\\b", Pattern.CASE_INSENSITIVE);
    }
}
