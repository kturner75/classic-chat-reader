package com.classicchatreader.config;

/**
 * Shared deployment-mode parse so boot checks and request gates cannot disagree.
 */
public final class DeploymentMode {

    public static final String PUBLIC = "public";
    public static final String LOCAL = "local";

    private DeploymentMode() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL;
        }
        return raw.trim();
    }

    public static boolean isPublic(String raw) {
        return PUBLIC.equalsIgnoreCase(normalize(raw));
    }
}
