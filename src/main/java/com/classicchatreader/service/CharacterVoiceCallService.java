package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.llm.LlmProviderException;
import com.classicchatreader.service.llm.XaiRealtimeSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Prepares everything the browser needs to hold a voice call with a character:
 * an ephemeral xAI realtime token plus the server-built session config (persona
 * instructions seeded with the shared text-chat history, assigned voice, and
 * turn-detection tuning). Audio never flows through this server.
 */
@Service
public class CharacterVoiceCallService {

    private static final Logger log = LoggerFactory.getLogger(CharacterVoiceCallService.class);

    /** Provider tag persisted with the voice; calls are currently hardwired to xAI. */
    static final String VOICE_PROVIDER = "xai";

    private final XaiRealtimeSessionService realtimeSessionService;
    private final CharacterPersonaPromptBuilder personaPromptBuilder;
    private final CharacterVoiceSelectionService voiceSelectionService;
    private final CharacterRepository characterRepository;
    private final ChapterRepository chapterRepository;

    @Value("${voice.call.enabled:false}")
    private boolean voiceCallEnabled;

    @Value("${voice.call.max-context-messages:${character.chat.max-context-messages:10}}")
    private int maxContextMessages;

    @Value("${voice.call.vad.threshold:0.5}")
    private double vadThreshold;

    @Value("${voice.call.vad.silence-duration-ms:600}")
    private int vadSilenceDurationMs;

    @Value("${voice.call.vad.idle-timeout-ms:30000}")
    private int vadIdleTimeoutMs;

    public CharacterVoiceCallService(
            XaiRealtimeSessionService realtimeSessionService,
            CharacterPersonaPromptBuilder personaPromptBuilder,
            CharacterVoiceSelectionService voiceSelectionService,
            CharacterRepository characterRepository,
            ChapterRepository chapterRepository) {
        this.realtimeSessionService = realtimeSessionService;
        this.personaPromptBuilder = personaPromptBuilder;
        this.voiceSelectionService = voiceSelectionService;
        this.characterRepository = characterRepository;
        this.chapterRepository = chapterRepository;
    }

    public record VoiceCallSession(String token, long expiresAtEpochSeconds, String model,
                                   String websocketUrl, SessionConfig sessionConfig) {}

    public record SessionConfig(String instructions, String voice, TurnDetection turnDetection) {}

    public record TurnDetection(String type, double threshold, int silenceDurationMs, int idleTimeoutMs) {}

    public boolean isVoiceCallEnabled() {
        return voiceCallEnabled;
    }

    public boolean isVoiceCallAvailable() {
        return voiceCallEnabled && realtimeSessionService.isAvailable();
    }

    /**
     * @throws LlmProviderException if the ephemeral token cannot be minted
     * @throws IllegalArgumentException if the character does not exist
     */
    public VoiceCallSession createSession(String characterId, List<ChatMessage> conversationHistory,
                                          int readerChapterIndex, int readerParagraphIndex) {
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));
        BookEntity book = character.getBook();

        String chapterTitle = chapterRepository.findByBookIdAndChapterIndex(book.getId(), readerChapterIndex)
                .map(ChapterEntity::getTitle)
                .orElse(null);

        String instructions = personaPromptBuilder.buildVoiceInstructions(
                character, book, readerChapterIndex, readerParagraphIndex, chapterTitle,
                conversationHistory, maxContextMessages);

        String voice = resolveVoice(character);

        XaiRealtimeSessionService.RealtimeSession session = realtimeSessionService.mintSession();

        log.info("event=voice_call_session_created character={} voice={} model={}",
                character.getName(), voice, session.model());

        return new VoiceCallSession(
                session.clientSecret(),
                session.expiresAtEpochSeconds(),
                session.model(),
                "wss://api.x.ai/v1/realtime?model=" + session.model(),
                new SessionConfig(
                        instructions,
                        voice,
                        new TurnDetection("server_vad", vadThreshold, vadSilenceDurationMs, vadIdleTimeoutMs)));
    }

    /**
     * Returns the persisted voice when one exists for the current provider; otherwise
     * selects one and persists it - but only LLM picks. Heuristic fallback picks are
     * deterministic and cheap, so leaving the column empty lets a later call retry
     * the LLM and upgrade to the full roster. Persistence is an atomic claim so
     * concurrent first callers converge on a single voice instead of racing.
     */
    private String resolveVoice(CharacterEntity character) {
        String persisted = character.getCallVoice();
        if (persisted != null && !persisted.isBlank()
                && VOICE_PROVIDER.equals(character.getCallVoiceProvider())) {
            return persisted;
        }

        CharacterVoiceSelectionService.VoiceSelection selection =
                voiceSelectionService.selectVoice(character.getName(), character.getDescription());

        if (selection.fromLlm()) {
            try {
                int claimed = characterRepository.claimCallVoice(
                        character.getId(), selection.voice(), VOICE_PROVIDER);
                if (claimed == 0) {
                    // A concurrent session claimed the assignment first - adopt its voice
                    // so simultaneous callers hear the same character.
                    String winner = characterRepository.findById(character.getId())
                            .filter(c -> VOICE_PROVIDER.equals(c.getCallVoiceProvider()))
                            .map(CharacterEntity::getCallVoice)
                            .orElse(null);
                    if (winner != null && !winner.isBlank()) {
                        log.info("event=voice_assignment_race_lost character={} adopted={} discarded={}",
                                character.getName(), winner, selection.voice());
                        return winner;
                    }
                }
            } catch (Exception e) {
                log.warn("event=voice_assignment_persist_failed character={} voice={} error={}",
                        character.getName(), selection.voice(), e.toString());
            }
        }
        return selection.voice();
    }
}
