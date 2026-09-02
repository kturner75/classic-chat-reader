package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePromptSafetyTest {

    @Test
    void classroomRulesAllowVisibleFaces() {
        assertFalse(ImagePromptSafety.LLM_RULES.contains("back-view"));
        assertFalse(ImagePromptSafety.LLM_RULES.contains("distant or back-view"));
        assertTrue(ImagePromptSafety.LLM_RULES.contains("Visible faces are fine"));
    }

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

    @Test
    void dropsBlockedStylePrefix() {
        String prepared = ImagePromptSafety.prepareForGeneration("nude watercolor, a garden scene");
        assertFalse(prepared.toLowerCase().contains("nude"));
        assertTrue(prepared.contains("atmospheric public setting"));
        assertTrue(prepared.contains(ImagePromptSafety.SUFFIX.trim()));
    }

    @Test
    void doesNotTrustSafetyPhraseInTheMiddleOfAPrompt() {
        String prepared = ImagePromptSafety.prepareForGeneration(
                "School-appropriate book illustration, nude portrait");
        assertFalse(prepared.toLowerCase().contains("nude"));
        assertTrue(prepared.endsWith(ImagePromptSafety.SUFFIX.trim())
                || prepared.endsWith(ImagePromptSafety.SUFFIX));
        assertTrue(prepared.contains("atmospheric public setting"));
    }

    @Test
    void rejectsInflectedSexualAndGraphicViolenceTerms() {
        assertTrue(ImagePromptSafety.isBlocked("sexualized child portrait"));
        assertTrue(ImagePromptSafety.isBlocked("sexually explicit bedroom scene"));
        assertTrue(ImagePromptSafety.isBlocked("graphic dismemberment and torture"));
        assertTrue(ImagePromptSafety.isBlocked("depict a rape scene"));
        assertTrue(ImagePromptSafety.isBlocked("graphic gore close-up"));
        assertTrue(ImagePromptSafety.isBlocked("suggestive pose of an adolescent"));
        assertTrue(ImagePromptSafety.isBlocked("graphic scene of soldiers torturing and dismembering prisoners"));
        assertTrue(ImagePromptSafety.isBlocked("sex scene between two adults"));
        assertTrue(ImagePromptSafety.isBlocked("bare-breasted portrait"));
        assertTrue(ImagePromptSafety.isBlocked("exposed breasts"));
        assertTrue(ImagePromptSafety.isBlocked("close-up beheading with exposed entrails"));
        assertTrue(ImagePromptSafety.isBlocked("shirtless portrait of an adolescent boy in a romantic pose"));
        assertFalse(ImagePromptSafety.isBlocked("bold woodcut, gory mere-flood, slain dragon"));
        assertFalse(ImagePromptSafety.isBlocked("Essex countryside at dusk"));
        assertFalse(ImagePromptSafety.isBlocked("warrior in mail breastplate on a longship"));
        String prepared = ImagePromptSafety.prepareForGeneration("sexualized child portrait");
        assertFalse(prepared.toLowerCase().contains("sexualized"));
        assertTrue(prepared.contains("atmospheric public setting"));
    }
}
