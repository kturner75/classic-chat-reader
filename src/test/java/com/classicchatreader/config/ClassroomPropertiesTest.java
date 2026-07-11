package com.classicchatreader.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassroomPropertiesTest {

    @Test
    void invalidModeFallsBackToHybrid() {
        ClassroomProperties properties = new ClassroomProperties();
        properties.setMode("typo-mode");
        assertEquals("hybrid", properties.getMode());
        assertTrue(properties.allowsDatabase());
        assertTrue(properties.allowsDemoFallback());
    }

    @Test
    void blankModeFallsBackToHybrid() {
        ClassroomProperties properties = new ClassroomProperties();
        properties.setMode("  ");
        assertEquals("hybrid", properties.getMode());
    }

    @Test
    void acceptsValidModes() {
        ClassroomProperties properties = new ClassroomProperties();
        properties.setMode("DATABASE");
        assertEquals("database", properties.getMode());
        properties.setMode("demo");
        assertEquals("demo", properties.getMode());
    }

    @Test
    void calendarZoneFallsBackOnInvalid() {
        ClassroomProperties properties = new ClassroomProperties();
        properties.setCalendarZone("Not/AZone");
        assertEquals(java.time.ZoneId.systemDefault(), properties.calendarZoneId());
    }
}
