package com.classicchatreader.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classroom-safe constraints for cover, chapter, and portrait image prompts.
 * Applied both when the LLM writes a prompt and again immediately before generation.
 */
final class ImagePromptSafety {

    static final String LLM_RULES = """
            CLASSROOM IMAGE SAFETY (required):
            - This image will be shown in a school reading app. Keep it suitable for classroom display.
            - Fully clothed period-appropriate dress only. No nudity, no lingerie, no sexual or suggestive poses, no erotic framing.
            - Never sexualize a child or adolescent. If a source character is a minor, show a public, non-romantic scene and keep them fully clothed.
            - Implied literary violence is allowed (a distant battle, a closed tomb). No graphic gore, dismemberment, torture, or sexual violence.
            - Prefer setting, objects, atmosphere, and distant or back-view figures over bodies.
            """;

    static final String SUFFIX = " School-appropriate book illustration. Fully clothed figures in period dress. "
            + "No nudity, no sexual content, no lingerie, no suggestive poses. "
            + "No graphic gore or sexual violence. Do not depict minors in romantic or sexual situations.";

    static final String COMFY_NEGATIVE = "nsfw, nude, naked, nudity, sexual, erotic, lingerie, nipples, genitals, "
            + "porn, explicit, gore, dismembered, decapitated, "
            + "text, watermark, blurry, bad quality, deformed, ugly, low resolution";

    static final String COMFY_COVER_NEGATIVE = COMFY_NEGATIVE
            + ", letters, words, typography, title, author name, signature, logo, cropped";

    static final String COMFY_PORTRAIT_NEGATIVE = COMFY_NEGATIVE
            + ", disfigured face, extra limbs";

    private static final Pattern BLOCKED = Pattern.compile(
            "(?i)(?<!\\p{L})("
                    + "nude|nudity|naked|topless|lingerie|erotic|erotica|porn|pornograph(?:y|ic)|"
                    + "nsfw|sexual|sexuality|seductive|undress(?:ed|ing)?|unclothed|"
                    + "nipples?|cleavage|genitals?|intercourse|orgasm|explicit sex"
                    + ")(?!\\p{L})"
    );

    private ImagePromptSafety() {
    }

    static String prepareForGeneration(String prompt) {
        String cleaned = prompt == null ? "" : prompt.trim();
        if (cleaned.isEmpty()) {
            return ("atmospheric literary book illustration plate." + SUFFIX).trim();
        }
        if (isBlocked(cleaned)) {
            cleaned = fallbackScene(withoutSuffix(cleaned));
        }
        if (!cleaned.toLowerCase(Locale.ROOT).contains("school-appropriate book illustration")) {
            cleaned = cleaned + SUFFIX;
        }
        return cleaned.trim();
    }

    static boolean isBlocked(String prompt) {
        return prompt != null && BLOCKED.matcher(withoutSuffix(prompt)).find();
    }

    private static String withoutSuffix(String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("school-appropriate book illustration");
        return idx >= 0 ? prompt.substring(0, idx).trim() : prompt;
    }

    private static String fallbackScene(String original) {
        String prefix = stylePrefix(original);
        return prefix
                + "atmospheric public setting from a classic book, distant architecture and landscape only, "
                + "no figures, classroom-appropriate book illustration plate.";
    }

    private static String stylePrefix(String original) {
        int comma = original.indexOf(',');
        if (comma > 0 && comma < 80) {
            return original.substring(0, comma + 1) + " ";
        }
        return "";
    }
}
