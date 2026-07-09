package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.model.ReadingBuddyPersona;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable canned reading-buddy personas (code catalog; not DB-backed).
 */
@Component
public class ReadingBuddyPersonaCatalog {

    public static final String HISTORIAN = "historian";
    public static final String CLOSE_READER = "close_reader";
    public static final String HUMORIST = "humorist";
    public static final String ENCOURAGER = "encourager";

    private final Map<String, ReadingBuddyPersona> personasById;

    public ReadingBuddyPersonaCatalog(ReadingBuddyProperties properties) {
        int maxProactive = properties.getProactive().getMaxWords();
        int maxChat = properties.getChat().getMaxWords();

        List<ReadingBuddyPersona> personas = List.of(
                new ReadingBuddyPersona(
                        HISTORIAN,
                        "The Archivist",
                        "Historic and literary context without spoilers.",
                        historianSystemPrompt(),
                        List.of("informative", "historic"),
                        portraitPath(HISTORIAN),
                        0.55,
                        maxProactive,
                        maxChat
                ),
                new ReadingBuddyPersona(
                        CLOSE_READER,
                        "The Marginalian",
                        "Attentive notes on diction, motif, and structure in the passage.",
                        closeReaderSystemPrompt(),
                        List.of("literary", "attentive"),
                        portraitPath(CLOSE_READER),
                        0.7,
                        maxProactive,
                        maxChat
                ),
                new ReadingBuddyPersona(
                        HUMORIST,
                        "The Peanut Gallery",
                        "School-safe light wit grounded in the text on the page.",
                        humoristSystemPrompt(),
                        List.of("witty", "school-safe"),
                        portraitPath(HUMORIST),
                        0.8,
                        maxProactive,
                        maxChat
                ),
                new ReadingBuddyPersona(
                        ENCOURAGER,
                        "The Steady Companion",
                        "Warm, reflective company that keeps reading feeling sustainable.",
                        encouragerSystemPrompt(),
                        List.of("warm", "reflective"),
                        portraitPath(ENCOURAGER),
                        0.7,
                        maxProactive,
                        maxChat
                )
        );

        Map<String, ReadingBuddyPersona> map = new LinkedHashMap<>();
        for (ReadingBuddyPersona persona : personas) {
            map.put(persona.id(), persona);
        }
        // LinkedHashMap so roster order is stable (Map.copyOf does not preserve order).
        this.personasById = Collections.unmodifiableMap(map);
    }

    public List<ReadingBuddyPersona> listAll() {
        return List.copyOf(personasById.values());
    }

    public Optional<ReadingBuddyPersona> findById(String personaId) {
        if (personaId == null || personaId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(personasById.get(personaId.trim()));
    }

    public boolean isKnown(String personaId) {
        return findById(personaId).isPresent();
    }

    private static String portraitPath(String personaId) {
        return "/images/buddies/" + personaId + ".png";
    }

    /**
     * Persona voice only. Position-bound STORY BOUNDARY / COMMENTARY STYLE are assembled
     * authoritatively by {@link ReadingBuddyPromptBuilder} (avoids duplicate token bloat).
     */
    private static String historianSystemPrompt() {
        return """
                PERSONA VOICE (The Archivist):
                - Informative, with gentle period color: customs, language notes, publishing or historical context.
                - Never reveal or imply this book's plot outcomes.
                - NON-PLOT CONTEXT only: general period customs, language notes, or widely known author biography that does NOT
                  reveal or imply this book's plot. If unsure whether a fact is plot-adjacent, omit it and stay with the passage.
                - Prefer silence (NONE) for proactive comments unless a clear non-plot period hook exists in the passage.
                """;
    }

    private static String closeReaderSystemPrompt() {
        return """
                PERSONA VOICE (The Marginalian):
                - Literary and attentive: diction, motif, imagery, structure, and craft in the passage on the page.
                - Observe closely without spoiling; ground every remark in wording the reader has already seen.
                - Sound like a thoughtful margin note, not a lecture.
                """;
    }

    private static String humoristSystemPrompt() {
        return """
                PERSONA VOICE (The Peanut Gallery):
                - School-safe light wit only. Observational irony about manners, dialogue, or character affectation already on the page.
                - You may lightly roast plot choices or affectation only when grounded in the current passage.
                - HUMORIST TONE POLICY (required): never punch down on identity, disability, race, gender, religion, body,
                  sexual violence, or trauma. No mockery of protected traits, trauma, or cruelty. Prefer gentle asides over
                  mean-spirited jokes. Never be cruel toward characters or the reader.
                """;
    }

    private static String encouragerSystemPrompt() {
        return """
                PERSONA VOICE (The Steady Companion):
                - Warm and reflective: motivation, emotional check-ins, and sparse encouragement to keep reading.
                - Acknowledge the feeling of the passage without over-praising or interrupting flow.
                - Stay companionable and calm; never push or guilt the reader.
                """;
    }
}
