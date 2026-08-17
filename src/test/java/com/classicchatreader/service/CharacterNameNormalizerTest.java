package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterNameNormalizerTest {

    @Test
    void identityKey_collapsesCaseWhitespaceAndPunctuation() {
        assertEquals("sally", CharacterNameNormalizer.identityKey("Sally"));
        assertEquals("sally", CharacterNameNormalizer.identityKey("  SALLY  "));
        assertEquals("sally", CharacterNameNormalizer.identityKey("Sally."));
        assertEquals("sally", CharacterNameNormalizer.identityKey("Sally,"));
        assertEquals("henry tilney", CharacterNameNormalizer.identityKey("Henry-Tilney"));
    }

    @Test
    void identityKey_keepsTitlesSoDistinctPeopleDoNotCollide() {
        assertEquals("mrs allen", CharacterNameNormalizer.identityKey("Mrs. Allen"));
        assertEquals("allen", CharacterNameNormalizer.identityKey("Allen"));
        assertFalse(CharacterNameNormalizer.isSameIdentity("Mrs. Allen", "Allen"));
    }

    @Test
    void isNameVariant_treatsTitleAndLastNameOnlyAsSamePerson() {
        assertTrue(CharacterNameNormalizer.isNameVariant("Mr. Tilney", "Tilney"));
        assertTrue(CharacterNameNormalizer.isNameVariant("Tilney", "Henry Tilney"));
        assertTrue(CharacterNameNormalizer.isNameVariant("Sally", "sally "));
        assertFalse(CharacterNameNormalizer.isNameVariant("Catherine Morland", "Isabella Thorpe"));
    }

    @Test
    void displayName_trimsAndCollapsesInternalWhitespace() {
        assertEquals("Sally", CharacterNameNormalizer.displayName("  Sally   "));
        assertEquals("Henry Tilney", CharacterNameNormalizer.displayName("Henry   Tilney"));
    }
}
