package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void identityKey_keepsTitlesAndGivenNamesSoDistinctPeopleDoNotCollide() {
        assertEquals("mrs allen", CharacterNameNormalizer.identityKey("Mrs. Allen"));
        assertEquals("mr allen", CharacterNameNormalizer.identityKey("Mr. Allen"));
        assertEquals("allen", CharacterNameNormalizer.identityKey("Allen"));
        assertEquals("mrs bennet", CharacterNameNormalizer.identityKey("Mrs. Bennet"));
        assertEquals("elizabeth bennet", CharacterNameNormalizer.identityKey("Elizabeth Bennet"));
        assertFalse(CharacterNameNormalizer.isSameIdentity("Mrs. Allen", "Allen"));
        assertFalse(CharacterNameNormalizer.isSameIdentity("Mr. Allen", "Mrs. Allen"));
        assertFalse(CharacterNameNormalizer.isSameIdentity("Mrs. Bennet", "Elizabeth Bennet"));
    }

    @Test
    void isNameVariant_isConservativeAndDoesNotMergeSharedSurnamesOrTitles() {
        assertTrue(CharacterNameNormalizer.isNameVariant("Sally", "sally "));
        assertTrue(CharacterNameNormalizer.isNameVariant("Sally", "Sally."));
        assertFalse(CharacterNameNormalizer.isNameVariant("Mrs. Bennet", "Elizabeth Bennet"));
        assertFalse(CharacterNameNormalizer.isNameVariant("Mr. Allen", "Mrs. Allen"));
        assertFalse(CharacterNameNormalizer.isNameVariant("Mr. Tilney", "Tilney"));
        assertFalse(CharacterNameNormalizer.isNameVariant("Tilney", "Henry Tilney"));
        assertFalse(CharacterNameNormalizer.isNameVariant("Catherine Morland", "Isabella Thorpe"));
    }

    @Test
    void identityKey_preservesUnicodeLettersAndDigits() {
        assertEquals("josé", CharacterNameNormalizer.identityKey("José"));
        assertEquals("jos", CharacterNameNormalizer.identityKey("Jos"));
        assertEquals("émile", CharacterNameNormalizer.identityKey("Émile"));
        assertEquals("mile", CharacterNameNormalizer.identityKey("Mile"));
        assertEquals("agent 47", CharacterNameNormalizer.identityKey("Agent 47"));
        assertEquals("agent", CharacterNameNormalizer.identityKey("Agent"));
        assertNotEquals(CharacterNameNormalizer.identityKey("José"), CharacterNameNormalizer.identityKey("Jos"));
        assertNotEquals(CharacterNameNormalizer.identityKey("Émile"), CharacterNameNormalizer.identityKey("Mile"));
        assertNotEquals(CharacterNameNormalizer.identityKey("Agent 47"), CharacterNameNormalizer.identityKey("Agent"));
        assertFalse(CharacterNameNormalizer.isNameVariant("José", "Jos"));
        assertFalse(CharacterNameNormalizer.isNameVariant("Émile", "Mile"));
        assertFalse(CharacterNameNormalizer.isNameVariant("Agent 47", "Agent"));
    }

    @Test
    void displayName_trimsAndCollapsesInternalWhitespace() {
        assertEquals("Sally", CharacterNameNormalizer.displayName("  Sally   "));
        assertEquals("Henry Tilney", CharacterNameNormalizer.displayName("Henry   Tilney"));
    }
}
