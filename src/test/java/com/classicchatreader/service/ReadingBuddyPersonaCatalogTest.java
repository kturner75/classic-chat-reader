package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.model.ReadingBuddyPersona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingBuddyPersonaCatalogTest {

    private ReadingBuddyPersonaCatalog catalog;

    @BeforeEach
    void setUp() {
        ReadingBuddyProperties properties = new ReadingBuddyProperties();
        properties.getProactive().setMaxWords(60);
        properties.getChat().setMaxWords(150);
        catalog = new ReadingBuddyPersonaCatalog(properties);
    }

    @Test
    void listAll_containsExactlyFourCannedPersonas() {
        List<ReadingBuddyPersona> personas = catalog.listAll();
        assertEquals(4, personas.size());

        Set<String> ids = personas.stream().map(ReadingBuddyPersona::id).collect(Collectors.toSet());
        assertEquals(Set.of(
                ReadingBuddyPersonaCatalog.HISTORIAN,
                ReadingBuddyPersonaCatalog.CLOSE_READER,
                ReadingBuddyPersonaCatalog.HUMORIST,
                ReadingBuddyPersonaCatalog.ENCOURAGER
        ), ids);
    }

    @Test
    void personas_haveDisplayNamesAndPortraitPaths() {
        assertEquals("The Archivist", catalog.findById("historian").orElseThrow().displayName());
        assertEquals("The Marginalian", catalog.findById("close_reader").orElseThrow().displayName());
        assertEquals("The Peanut Gallery", catalog.findById("humorist").orElseThrow().displayName());
        assertEquals("The Steady Companion", catalog.findById("encourager").orElseThrow().displayName());

        for (ReadingBuddyPersona persona : catalog.listAll()) {
            assertEquals("/images/buddies/" + persona.id() + ".png", persona.portraitPath());
            assertFalse(persona.shortBlurb().isBlank());
            assertFalse(persona.systemPrompt().isBlank());
            assertFalse(persona.toneTags().isEmpty());
            assertEquals(60, persona.maxProactiveWords());
            assertEquals(150, persona.maxChatWords());
        }
    }

    @Test
    void historian_usesLowerTemperatureAndNonPlotGuidance() {
        ReadingBuddyPersona historian = catalog.findById(ReadingBuddyPersonaCatalog.HISTORIAN).orElseThrow();
        assertEquals(0.55, historian.temperature());
        assertTrue(historian.systemPrompt().toLowerCase().contains("non-plot"));
        assertTrue(historian.systemPrompt().toLowerCase().contains("never"));
    }

    @Test
    void humorist_systemPrompt_includesSchoolSafePolicy() {
        ReadingBuddyPersona humorist = catalog.findById(ReadingBuddyPersonaCatalog.HUMORIST).orElseThrow();
        String prompt = humorist.systemPrompt().toLowerCase();
        assertTrue(prompt.contains("school-safe"));
        assertTrue(prompt.contains("protected") || prompt.contains("punch down") || prompt.contains("trauma"));
    }

    @Test
    void findById_unknownOrBlank_isEmpty() {
        assertTrue(catalog.findById("unknown").isEmpty());
        assertTrue(catalog.findById("").isEmpty());
        assertTrue(catalog.findById(null).isEmpty());
        assertFalse(catalog.isKnown("nope"));
        assertTrue(catalog.isKnown(ReadingBuddyPersonaCatalog.CLOSE_READER));
    }

    @Test
    void listOrder_isStableRosterOrder() {
        List<String> ids = catalog.listAll().stream().map(ReadingBuddyPersona::id).toList();
        assertEquals(List.of("historian", "close_reader", "humorist", "encourager"), ids);
    }
}
