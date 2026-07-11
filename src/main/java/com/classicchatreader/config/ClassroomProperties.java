package com.classicchatreader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(ClassroomProperties.class);
    private static final Set<String> VALID_MODES = Set.of("demo", "database", "hybrid");

    /**
     * demo | database | hybrid
     */
    private String mode = "hybrid";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        if (mode == null || mode.isBlank()) {
            this.mode = "hybrid";
            return;
        }
        String normalized = mode.trim().toLowerCase();
        if (!VALID_MODES.contains(normalized)) {
            log.warn("Invalid classroom.mode='{}'; falling back to hybrid", mode);
            this.mode = "hybrid";
            return;
        }
        this.mode = normalized;
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
