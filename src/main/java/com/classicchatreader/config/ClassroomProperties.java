package com.classicchatreader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Classroom persistence mode (BL-025).
 * <ul>
 *   <li>{@code demo} — process-global demo properties only</li>
 *   <li>{@code database} — DB membership only (pilot prod)</li>
 *   <li>{@code hybrid} — DB membership first, then demo if none (default for local)</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "classroom")
public class ClassroomProperties {

    /**
     * demo | database | hybrid
     */
    private String mode = "hybrid";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "hybrid" : mode.trim().toLowerCase();
    }

    public boolean isDemoMode() {
        return "demo".equals(getMode());
    }

    public boolean isDatabaseMode() {
        return "database".equals(getMode());
    }

    public boolean isHybridMode() {
        return "hybrid".equals(getMode());
    }

    public boolean allowsDatabase() {
        return isDatabaseMode() || isHybridMode();
    }

    public boolean allowsDemoFallback() {
        return isDemoMode() || isHybridMode();
    }
}
