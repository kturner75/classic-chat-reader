package com.classicchatreader.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks one of the five built-in xAI voices for a character, deterministically,
 * from the character's name and LLM-written description. No schema change and no
 * extra LLM call: gender is inferred from pronouns/titles in the description text
 * (which is reliably pronoun-rich prose), and the pick within the gender pool is
 * a stable hash of the name so it survives character re-extraction (UUIDs don't).
 */
@Component
public class CharacterVoiceAssigner {

    private static final List<String> FEMALE_VOICES = List.of("eve", "ara");
    private static final List<String> MALE_VOICES = List.of("rex", "leo", "sal");
    private static final List<String> ALL_VOICES = List.of("eve", "ara", "rex", "leo", "sal");

    private static final Pattern FEMALE_MARKERS = Pattern.compile(
            "\\b(she|her|hers|herself|mrs|miss|ms|lady|woman|girl|wife|mother|daughter|sister|aunt|"
                    + "queen|princess|duchess|countess|madame|mademoiselle|matron|widow|heroine)\\b");

    private static final Pattern MALE_MARKERS = Pattern.compile(
            "\\b(he|him|his|himself|mr|sir|lord|man|boy|husband|father|son|brother|uncle|"
                    + "king|prince|duke|count|monsieur|gentleman|widower|hero)\\b");

    public String assignVoice(String name, String description) {
        String text = ((name != null ? name : "") + " " + (description != null ? description : ""))
                .toLowerCase(Locale.ROOT);

        int femaleCount = countMatches(FEMALE_MARKERS, text);
        int maleCount = countMatches(MALE_MARKERS, text);

        List<String> pool;
        if (femaleCount > maleCount) {
            pool = FEMALE_VOICES;
        } else if (maleCount > femaleCount) {
            pool = MALE_VOICES;
        } else {
            pool = ALL_VOICES;
        }

        String key = name != null ? name.toLowerCase(Locale.ROOT) : "";
        return pool.get(Math.floorMod(key.hashCode(), pool.size()));
    }

    private int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
