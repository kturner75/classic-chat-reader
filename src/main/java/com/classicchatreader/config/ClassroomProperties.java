package com.classicchatreader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    /**
     * IANA zone for calendar DATE comparisons (available_from open day).
     * Empty → JVM default zone (aligns better with local school deployments than hard-coded UTC).
     */
    private String calendarZone = "";

    /**
     * Default classroom invite TTL and use cap (BL-043.4 / SECURITY_AUDIT M-03).
     */
    private Invite invite = new Invite();

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

    public String getCalendarZone() {
        return calendarZone;
    }

    public void setCalendarZone(String calendarZone) {
        this.calendarZone = calendarZone == null ? "" : calendarZone.trim();
    }

    public ZoneId calendarZoneId() {
        if (calendarZone == null || calendarZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(calendarZone);
        } catch (Exception e) {
            log.warn("Invalid classroom.calendar-zone='{}'; using system default", calendarZone);
            return ZoneId.systemDefault();
        }
    }

    /** Today as a calendar date in {@link #calendarZoneId()} for available_from gates. */
    public LocalDate today() {
        return LocalDate.now(calendarZoneId());
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

    public Invite getInvite() {
        return invite;
    }

    public void setInvite(Invite invite) {
        this.invite = invite == null ? new Invite() : invite;
    }

    public int inviteDefaultMaxUses() {
        return invite.getDefaultMaxUses();
    }

    public int inviteDefaultTtlDays() {
        return invite.getDefaultTtlDays();
    }

    public LocalDateTime inviteDefaultExpiresAt() {
        return LocalDateTime.now(ZoneOffset.UTC).plusDays(invite.getDefaultTtlDays());
    }

    public static class Invite {
        private int defaultTtlDays = 30;
        private int defaultMaxUses = 40;

        public int getDefaultTtlDays() {
            return defaultTtlDays;
        }

        public void setDefaultTtlDays(int defaultTtlDays) {
            if (defaultTtlDays < 1) {
                log.warn("Invalid classroom.invite.default-ttl-days={}; using 30", defaultTtlDays);
                this.defaultTtlDays = 30;
                return;
            }
            this.defaultTtlDays = defaultTtlDays;
        }

        public int getDefaultMaxUses() {
            return defaultMaxUses;
        }

        public void setDefaultMaxUses(int defaultMaxUses) {
            if (defaultMaxUses < 1) {
                log.warn("Invalid classroom.invite.default-max-uses={}; using 40", defaultMaxUses);
                this.defaultMaxUses = 40;
                return;
            }
            this.defaultMaxUses = defaultMaxUses;
        }
    }
}
