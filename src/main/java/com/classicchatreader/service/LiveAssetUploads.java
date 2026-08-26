package com.classicchatreader.service;

/**
 * Shared provenance helpers for cover / portrait / illustration write-back.
 */
public final class LiveAssetUploads {

    public static final String SOURCE_MANUAL_UPLOAD = "manual_upload";
    public static final String SOURCE_STUDIO = "studio";

    private static final int MAX_SOURCE_LENGTH = 64;
    private static final int MAX_PROMPT_LENGTH = 2000;

    private LiveAssetUploads() {
    }

    public static String resolveSource(String source) {
        if (source == null || source.isBlank()) {
            return SOURCE_MANUAL_UPLOAD;
        }
        String trimmed = source.trim();
        return trimmed.length() > MAX_SOURCE_LENGTH ? trimmed.substring(0, MAX_SOURCE_LENGTH) : trimmed;
    }

    public static String normalizePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String trimmed = prompt.trim();
        return trimmed.length() > MAX_PROMPT_LENGTH ? trimmed.substring(0, MAX_PROMPT_LENGTH) : trimmed;
    }

    /**
     * Portraits and illustrations have a single prompt column. Prefer the override
     * when studio sends both, otherwise the generated prompt.
     */
    public static String resolveStoredPrompt(String generatedPrompt, String promptOverride) {
        String override = normalizePrompt(promptOverride);
        if (override != null) {
            return override;
        }
        return normalizePrompt(generatedPrompt);
    }
}
