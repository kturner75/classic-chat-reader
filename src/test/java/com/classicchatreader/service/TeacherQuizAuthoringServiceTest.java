package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeacherQuizAuthoringServiceTest {

    @Test
    void formatExcludedChoices_listsExistingLabels() {
        assertEquals("", TeacherQuizAuthoringService.formatExcludedChoices(null));
        assertEquals("", TeacherQuizAuthoringService.formatExcludedChoices(List.of("  ", "")));
        assertEquals(
                " Do not reuse these existing choices: Paris; London.",
                TeacherQuizAuthoringService.formatExcludedChoices(List.of("Paris", " London ", "Paris")));
    }

    @Test
    void addExcludedChoices_isCaseInsensitive() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        TeacherQuizAuthoringService.addExcludedChoices(seen, List.of("Paris", " london "));
        assertTrue(seen.contains("paris"));
        assertTrue(seen.contains("london"));
        assertFalse(seen.contains("London"));
    }
}
