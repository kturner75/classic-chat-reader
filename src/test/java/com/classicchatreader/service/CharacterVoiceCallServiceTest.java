package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.CharacterVoiceSelectionService.VoiceSelection;
import com.classicchatreader.service.llm.XaiRealtimeSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterVoiceCallServiceTest {

    @Mock
    private XaiRealtimeSessionService realtimeSessionService;

    @Mock
    private CharacterVoiceSelectionService voiceSelectionService;

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private ChapterRepository chapterRepository;

    private CharacterVoiceCallService service;

    @BeforeEach
    void setUp() {
        service = new CharacterVoiceCallService(
                realtimeSessionService,
                new CharacterPersonaPromptBuilder(),
                voiceSelectionService,
                characterRepository,
                chapterRepository);
        ReflectionTestUtils.setField(service, "voiceCallEnabled", true);
        ReflectionTestUtils.setField(service, "maxContextMessages", 10);
        ReflectionTestUtils.setField(service, "vadThreshold", 0.5);
        ReflectionTestUtils.setField(service, "vadSilenceDurationMs", 600);
        ReflectionTestUtils.setField(service, "vadIdleTimeoutMs", 30000);
    }

    @Test
    void isVoiceCallAvailable_requiresEnabledFlagAndProvider() {
        when(realtimeSessionService.isAvailable()).thenReturn(true);
        assertTrue(service.isVoiceCallAvailable());

        ReflectionTestUtils.setField(service, "voiceCallEnabled", false);
        assertFalse(service.isVoiceCallAvailable());
    }

    @Test
    void createSession_buildsInstructionsVoiceAndConfig() {
        CharacterEntity character = characterInBook();
        when(characterRepository.findByIdWithBookAndChapter("char-1")).thenReturn(Optional.of(character));
        ChapterEntity chapter = new ChapterEntity(2, "The Turning Point");
        when(chapterRepository.findByBookIdAndChapterIndex(character.getBook().getId(), 2))
                .thenReturn(Optional.of(chapter));
        when(voiceSelectionService.selectVoice(character.getName(), character.getDescription()))
                .thenReturn(new VoiceSelection("atlas", "Grounded voice suits a detective", true));
        when(realtimeSessionService.mintSession())
                .thenReturn(new XaiRealtimeSessionService.RealtimeSession("secret", 42L, "grok-voice-latest"));

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "Tell me about the moor.", 1L),
                new ChatMessage("character", "It is a bleak and wondrous place, my friend.", 2L));

        CharacterVoiceCallService.VoiceCallSession session =
                service.createSession("char-1", history, 2, 7);

        assertEquals("secret", session.token());
        assertEquals(42L, session.expiresAtEpochSeconds());
        assertEquals("wss://api.x.ai/v1/realtime?model=grok-voice-latest", session.websocketUrl());

        String instructions = session.sessionConfig().instructions();
        assertTrue(instructions.contains("Sherlock Holmes"), "persona should name the character");
        assertTrue(instructions.contains("The Turning Point"), "persona should include chapter title");
        assertTrue(instructions.contains("Tell me about the moor."), "history should be embedded");
        assertTrue(instructions.contains("VOICE CALL RULES"), "voice addendum should be present");

        assertEquals("atlas", session.sessionConfig().voice());

        CharacterVoiceCallService.TurnDetection vad = session.sessionConfig().turnDetection();
        assertEquals("server_vad", vad.type());
        assertEquals(0.5, vad.threshold());
        assertEquals(600, vad.silenceDurationMs());
        assertEquals(30000, vad.idleTimeoutMs());
    }

    @Test
    void createSession_llmPick_persistsVoiceAndProvider() {
        CharacterEntity character = characterInBook();
        stubSessionCollaborators(character);
        when(voiceSelectionService.selectVoice(character.getName(), character.getDescription()))
                .thenReturn(new VoiceSelection("luna", "fits", true));
        when(characterRepository.claimCallVoice("char-1", "luna", "xai")).thenReturn(1);

        CharacterVoiceCallService.VoiceCallSession session =
                service.createSession("char-1", List.of(), 2, 7);

        assertEquals("luna", session.sessionConfig().voice());
        verify(characterRepository).claimCallVoice("char-1", "luna", "xai");
    }

    @Test
    void createSession_heuristicFallback_isNotPersisted() {
        CharacterEntity character = characterInBook();
        stubSessionCollaborators(character);
        when(voiceSelectionService.selectVoice(character.getName(), character.getDescription()))
                .thenReturn(new VoiceSelection("rex", null, false));

        CharacterVoiceCallService.VoiceCallSession session =
                service.createSession("char-1", List.of(), 2, 7);

        assertEquals("rex", session.sessionConfig().voice());
        verify(characterRepository, never()).claimCallVoice(anyString(), anyString(), anyString());
    }

    @Test
    void createSession_persistedVoice_reusedWithoutSelection() {
        CharacterEntity character = characterInBook();
        character.setCallVoice("celeste");
        character.setCallVoiceProvider("xai");
        stubSessionCollaborators(character);

        CharacterVoiceCallService.VoiceCallSession session =
                service.createSession("char-1", List.of(), 2, 7);

        assertEquals("celeste", session.sessionConfig().voice());
        verify(voiceSelectionService, never()).selectVoice(anyString(), anyString());
        verify(characterRepository, never()).claimCallVoice(anyString(), anyString(), anyString());
    }

    @Test
    void createSession_providerMismatch_reselectsAndOverwrites() {
        CharacterEntity character = characterInBook();
        character.setCallVoice("some-openai-voice");
        character.setCallVoiceProvider("openai");
        stubSessionCollaborators(character);
        when(voiceSelectionService.selectVoice(character.getName(), character.getDescription()))
                .thenReturn(new VoiceSelection("atlas", "fits", true));
        when(characterRepository.claimCallVoice("char-1", "atlas", "xai")).thenReturn(1);

        CharacterVoiceCallService.VoiceCallSession session =
                service.createSession("char-1", List.of(), 2, 7);

        assertEquals("atlas", session.sessionConfig().voice());
        verify(characterRepository).claimCallVoice("char-1", "atlas", "xai");
    }

    @Test
    void createSession_concurrentClaimLost_adoptsWinningVoice() {
        CharacterEntity character = characterInBook();
        stubSessionCollaborators(character);
        when(voiceSelectionService.selectVoice(character.getName(), character.getDescription()))
                .thenReturn(new VoiceSelection("luna", "fits", true));
        when(characterRepository.claimCallVoice("char-1", "luna", "xai")).thenReturn(0);

        CharacterEntity winner = characterInBook();
        winner.setCallVoice("atlas");
        winner.setCallVoiceProvider("xai");
        when(characterRepository.findById("char-1")).thenReturn(Optional.of(winner));

        CharacterVoiceCallService.VoiceCallSession session =
                service.createSession("char-1", List.of(), 2, 7);

        assertEquals("atlas", session.sessionConfig().voice(),
                "losing session should adopt the concurrently persisted voice");
    }

    @Test
    void createSession_persistFailure_stillReturnsSession() {
        CharacterEntity character = characterInBook();
        stubSessionCollaborators(character);
        when(voiceSelectionService.selectVoice(character.getName(), character.getDescription()))
                .thenReturn(new VoiceSelection("luna", "fits", true));
        when(characterRepository.claimCallVoice("char-1", "luna", "xai"))
                .thenThrow(new RuntimeException("db down"));

        CharacterVoiceCallService.VoiceCallSession session =
                service.createSession("char-1", List.of(), 2, 7);

        assertEquals("luna", session.sessionConfig().voice());
    }

    @Test
    void createSession_unknownCharacter_throws() {
        when(characterRepository.findByIdWithBookAndChapter("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createSession("missing", List.of(), 0, 0));
    }

    private void stubSessionCollaborators(CharacterEntity character) {
        when(characterRepository.findByIdWithBookAndChapter("char-1")).thenReturn(Optional.of(character));
        when(chapterRepository.findByBookIdAndChapterIndex(character.getBook().getId(), 2))
                .thenReturn(Optional.of(new ChapterEntity(2, "The Turning Point")));
        when(realtimeSessionService.mintSession())
                .thenReturn(new XaiRealtimeSessionService.RealtimeSession("secret", 42L, "grok-voice-latest"));
    }

    private CharacterEntity characterInBook() {
        BookEntity book = new BookEntity("The Hound of the Baskervilles", "Arthur Conan Doyle", "gutenberg");
        book.setId("book-1");
        CharacterEntity character = new CharacterEntity();
        character.setId("char-1");
        character.setBook(book);
        character.setName("Sherlock Holmes");
        character.setDescription("He is a consulting detective; his powers of observation astonish. Mr Holmes is unflappable.");
        return character;
    }
}
