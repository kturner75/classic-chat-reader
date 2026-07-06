package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterVoiceAssignerTest {

    private final CharacterVoiceAssigner assigner = new CharacterVoiceAssigner();

    @Test
    void assignVoice_femaleDescription_picksFemaleVoice() {
        String voice = assigner.assignVoice("Elizabeth Bennet",
                "The second of five daughters, she is witty and independent. "
                        + "Her mother wishes to see her married; she resists.");
        assertTrue(List.of("eve", "ara").contains(voice), "expected female voice, got " + voice);
    }

    @Test
    void assignVoice_maleDescription_picksMaleVoice() {
        String voice = assigner.assignVoice("Sherlock Holmes",
                "A consulting detective. He is brilliant and eccentric; his methods baffle Scotland Yard. "
                        + "Mr Holmes shares rooms with his friend and biographer.");
        assertTrue(List.of("rex", "leo", "sal").contains(voice), "expected male voice, got " + voice);
    }

    @Test
    void assignVoice_ambiguousDescription_picksFromFullPool() {
        String voice = assigner.assignVoice("The Stranger", "A mysterious figure of unknown origin.");
        assertTrue(List.of("eve", "ara", "rex", "leo", "sal").contains(voice));
    }

    @Test
    void assignVoice_isDeterministicForSameName() {
        String first = assigner.assignVoice("Sherlock Holmes", "He is a detective. His work is famous.");
        String second = assigner.assignVoice("Sherlock Holmes", "He is a detective. His work is famous.");
        assertEquals(first, second);
    }

    @Test
    void assignVoice_handlesNullsWithoutThrowing() {
        String voice = assigner.assignVoice(null, null);
        assertTrue(List.of("eve", "ara", "rex", "leo", "sal").contains(voice));
    }
}
