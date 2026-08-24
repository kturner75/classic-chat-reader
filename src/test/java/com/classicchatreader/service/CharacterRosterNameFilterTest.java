package com.classicchatreader.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterRosterNameFilterTest {

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            "bees",
            "The Moon",
            "The Mule",
            "Moon",
            "Mule",
            "maid",
            "the maid",
            "The Maid",
            "Elizabeth Lavenza (again)"
    })
    void rejectsNonPersonAndGlitchNames(String name) {
        assertFalse(CharacterRosterNameFilter.isClearlyNamed(name), name);
    }

    @ParameterizedTest(name = "keeps {0}")
    @ValueSource(strings = {
            "Dorian",
            "Fortunato",
            "Elizabeth Bennet",
            "The Monster",
            "The Creature",
            "The Turk"
    })
    void keepsNamedPeople(String name) {
        assertTrue(CharacterRosterNameFilter.isClearlyNamed(name), name);
    }
}
