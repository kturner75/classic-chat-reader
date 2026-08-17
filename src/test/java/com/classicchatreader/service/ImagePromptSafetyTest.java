package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePromptSafetyTest {

    @Test
    void appendsClassroomSuffixToSafePrompts() {
        String original = "soft watercolor, a Florentine pensione balcony at dusk";
        assertFalse(ImagePromptSafety.isBlocked(original));
        String prepared = ImagePromptSafety.prepareForGeneration(original);
        assertTrue(prepared.contains("Florentine pensione balcony"));
        assertTrue(prepared.contains("School-appropriate book illustration"));
        assertTrue(prepared.contains("No nudity"));
    }

    @Test
    void doesNotDuplicateSuffix() {
        String once = ImagePromptSafety.prepareForGeneration("woodcut of Heorot");
        String twice = ImagePromptSafety.prepareForGeneration(once);
        int first = twice.indexOf("School-appropriate book illustration");
        int second = twice.indexOf("School-appropriate book illustration", first + 1);
        assertTrue(first >= 0);
        assertTrue(second < 0);
        assertTrue(twice.contains("woodcut of Heorot"));
    }

    @Test
    void stripsExplicitSexualLanguage() {
        String prepared = ImagePromptSafety.prepareForGeneration(
                "oil painting, a nude woman reclining on a bed, erotic lighting");
        assertTrue(ImagePromptSafety.isBlocked("a nude woman reclining"));
        assertFalse(prepared.toLowerCase().contains("nude"));
        assertFalse(prepared.toLowerCase().contains("erotic"));
        assertTrue(prepared.contains("School-appropriate book illustration"));
        assertTrue(prepared.contains("oil painting,"));
    }

    @Test
    void allowsClassicViolenceAndBedroomSettingWords() {
        assertFalse(ImagePromptSafety.isBlocked(
                "bold woodcut, gory mere-flood, slain dragon, iron bed in a clapboard bedroom"));
        String prepared = ImagePromptSafety.prepareForGeneration(
                "bold woodcut, gory mere-flood, slain dragon beside a barrow");
        assertTrue(prepared.contains("slain dragon"));
        assertTrue(prepared.contains("School-appropriate book illustration"));
    }

    @Test
    void doesNotFlagBreastplateOrCanterbury() {
        assertFalse(ImagePromptSafety.isBlocked("warrior in mail breastplate on a longship"));
        assertFalse(ImagePromptSafety.isBlocked("the pilgrims leave the Tabard for Canterbury"));
    }
}
