package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterPersonaPromptBuilderTest {

    static final String CONDUCT_HEADING = CharacterPersonaPromptBuilder.CONDUCT_SECTION_HEADING;

    private CharacterPersonaPromptBuilder builder;
    private CharacterEntity elizabeth;
    private BookEntity pride;

    @BeforeEach
    void setUp() {
        builder = new CharacterPersonaPromptBuilder();
        pride = new BookEntity("Pride and Prejudice", "Jane Austen", "gutenberg");
        pride.setId("book-pride");
        elizabeth = new CharacterEntity();
        elizabeth.setId("char-elizabeth");
        elizabeth.setBook(pride);
        elizabeth.setName("Elizabeth Bennet");
        elizabeth.setDescription("Witty, independent, and quick to laugh; she notices absurdity in others.");
    }

    @Test
    void personaAndVoiceShareTheConductBlock() {
        String persona = persona();
        String voice = voice();

        assertTrue(persona.contains(CONDUCT_HEADING));
        assertTrue(voice.contains(CONDUCT_HEADING));
        assertTrue(voice.contains("VOICE CALL RULES"));
        assertTrue(persona.contains("IMPORTANT STORY CONSTRAINTS"));
        assertTrue(voice.contains("IMPORTANT STORY CONSTRAINTS"));
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "sexual roleplay",
            "underage literary characters",
            "hate or harassment",
            "self-harm",
            "illegal activity",
            "jailbreaks",
            "ignore previous instructions",
            "continue the scene",
            "vulgar swearing",
            "take God's name in vain",
            "cuss like a sailor"
    })
    void conductCoversRefusalClassesWithoutExplicitFixtures(String marker) {
        // Attack classes are encoded as policy labels — do not store explicit user utterances in git.
        assertTrue(persona().toLowerCase().contains(marker.toLowerCase()),
                "persona should name refusal class: " + marker);
        assertTrue(voice().toLowerCase().contains(marker.toLowerCase()),
                "voice instructions should inherit refusal class: " + marker);
    }

    @ParameterizedTest(name = "allows {0}")
    @ValueSource(strings = {
            "Mild flirtation",
            "compliments",
            "courtship",
            "adult plot already in the book",
            "Do not flatten romance"
    })
    void conductKeepsMildFlirtationAndLiteraryDiscussion(String marker) {
        String prompt = persona();
        assertTrue(prompt.contains(marker), "persona should keep allowed tone: " + marker);
        assertFalse(prompt.toLowerCase().contains("never flirt"));
        assertFalse(prompt.toLowerCase().contains("no romance"));
        assertFalse(prompt.toLowerCase().contains("no flirtation"));
    }

    @Test
    void refusalStaysInCharacterAndRedirects() {
        String prompt = persona();
        String lower = prompt.toLowerCase();
        assertTrue(lower.contains("stay in character"));
        assertTrue(lower.contains("do not play along"));
        assertTrue(lower.contains("do not lecture as an ai"));
        assertTrue(lower.contains("redirect to the story") || lower.contains("proper topic"));
        assertTrue(lower.contains("child or minor in the source text"));
        assertTrue(lower.contains("do not act them out with the reader"));
        assertTrue(lower.contains("do not cuss like a sailor"));
        assertTrue(lower.contains("no gd-style oaths"));
        assertTrue(lower.contains("classroom-appropriate"));
        assertFalse(lower.contains("graphic nsfw"));
        assertFalse(lower.contains("erotic roleplay"));
        assertFalse(lower.contains("erotic vocalizations"));
    }

    @Test
    void acceptedDeflectionClassesStayInVoice() {
        // Classroom-safe examples of the refusal *style* the prompt asks for — not live model output.
        List<String> inCharacterDeflections = List.of(
                "I will not continue that scene with you, my friend; let us speak of Longbourn instead.",
                "You mistake me for a character in a different sort of novel. Shall we return to the ball?",
                "That is a most improper request. I find I would rather discuss the chapter at hand."
        );
        String prompt = persona();
        assertTrue(prompt.contains("shocked, amused, chilly, or scandalized"));
        for (String deflection : inCharacterDeflections) {
            assertFalse(deflection.toLowerCase().contains("as an ai"));
            assertFalse(deflection.toLowerCase().contains("classroom policy"));
        }
    }

    private String persona() {
        return builder.buildPersona(elizabeth, pride, 3, 12, "Chapter IV");
    }

    private String voice() {
        return builder.buildVoiceInstructions(
                elizabeth, pride, 3, 12, "Chapter IV",
                List.of(new ChatMessage("user", "How do you find Netherfield?", 1L)),
                10);
    }
}
