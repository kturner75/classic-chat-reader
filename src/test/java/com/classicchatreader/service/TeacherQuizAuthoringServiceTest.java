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
    void formatExcludedChoices_capsItemCountAndLength() {
        List<String> exclude = new java.util.ArrayList<>();
        exclude.add("a".repeat(TeacherQuizAuthoringService.MAX_EXCLUDED_CHOICE_LENGTH + 40));
        for (int i = 0; i < TeacherQuizAuthoringService.MAX_EXCLUDED_CHOICES + 5; i++) {
            exclude.add("choice-" + i);
        }

        String formatted = TeacherQuizAuthoringService.formatExcludedChoices(exclude);

        assertTrue(formatted.startsWith(" Do not reuse these existing choices: "));
        assertFalse(formatted.contains("choice-" + TeacherQuizAuthoringService.MAX_EXCLUDED_CHOICES));
        assertEquals(
                TeacherQuizAuthoringService.MAX_EXCLUDED_CHOICE_LENGTH,
                formatted.substring(" Do not reuse these existing choices: ".length())
                        .split("; ", 2)[0]
                        .length());
        assertEquals(
                TeacherQuizAuthoringService.MAX_EXCLUDED_CHOICES,
                formatted.split("; ").length);
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
