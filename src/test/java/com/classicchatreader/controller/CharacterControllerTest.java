package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.AccountChatHistoryService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.CharacterChatService;
import com.classicchatreader.service.CharacterExtractionService;
import com.classicchatreader.service.CharacterPrefetchService;
import com.classicchatreader.service.CharacterService;
import com.classicchatreader.service.CharacterVoiceCallService;
import com.classicchatreader.service.ComfyUIService;
import com.classicchatreader.service.llm.LlmProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CharacterController.class)
@TestPropertySource(properties = {
        "generation.cache-only=false",
        "character.enabled=true",
        "ai.chat.enabled=true",
        "illustration.allow-prompt-editing=true"
})
class CharacterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CharacterService characterService;

    @MockitoBean
    private CharacterChatService chatService;

    @MockitoBean
    private CharacterVoiceCallService voiceCallService;

    @MockitoBean
    private CharacterExtractionService extractionService;

    @MockitoBean
    private CharacterPrefetchService prefetchService;

    @MockitoBean
    private ComfyUIService comfyUIService;

    @MockitoBean
    private CdnAssetService cdnAssetService;

    @MockitoBean
    private BookRepository bookRepository;

    @MockitoBean
    private ChapterRepository chapterRepository;

    @MockitoBean
    private AccountAuthService accountAuthService;

    @MockitoBean
    private AccountChatHistoryService accountChatHistoryService;

    @Test
    void getCharactersForBook_missingBook_returnsNotFound() throws Exception {
        when(bookRepository.findById("book-missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/characters/book/book-missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCharactersForBook_whenBookCharacterModeDisabled_returnsForbidden() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(false);
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));

        mockMvc.perform(get("/api/characters/book/book-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestPortrait_primaryCharacter_queuesGeneration() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));

        mockMvc.perform(post("/api/characters/character-1/portrait/request"))
                .andExpect(status().isAccepted());

        verify(characterService).requestPortrait("character-1");
        verify(prefetchService, never()).prefetchCharactersForBook(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void requestPortrait_secondaryCharacter_returnsForbidden() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.SECONDARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));

        mockMvc.perform(post("/api/characters/character-1/portrait/request"))
                .andExpect(status().isForbidden());

        verify(characterService, never()).requestPortrait("character-1");
    }

    @Test
    void regeneratePortrait_primaryCharacter_queuesCustomPrompt() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));

        mockMvc.perform(post("/api/characters/character-1/portrait/regenerate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "prompt": "Elizabeth Bennet in a pale muslin gown"
                                }
                                """))
                .andExpect(status().isAccepted());

        verify(characterService).regeneratePortraitWithPrompt(
                "character-1", "Elizabeth Bennet in a pale muslin gown");
        verify(prefetchService, never()).prefetchCharactersForBook(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void regeneratePortrait_blankPrompt_returnsBadRequest() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));

        mockMvc.perform(post("/api/characters/character-1/portrait/regenerate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "prompt": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(characterService, never()).regeneratePortraitWithPrompt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void regeneratePortrait_alreadyGenerating_returnsConflict() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);
        character.setStatus(com.classicchatreader.entity.CharacterStatus.GENERATING);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));

        mockMvc.perform(post("/api/characters/character-1/portrait/regenerate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "prompt": "Elizabeth Bennet in a pale muslin gown"
                                }
                                """))
                .andExpect(status().isConflict());

        verify(characterService, never()).regeneratePortraitWithPrompt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void requestChapterAnalysis_enabledBook_queuesAnalysis() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));

        mockMvc.perform(post("/api/characters/chapter/chapter-1/analyze"))
                .andExpect(status().isAccepted());

        verify(characterService).requestChapterAnalysis("chapter-1");
    }

    @Test
    void chat_secondaryCharacter_returnsMainCharacterMessage() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.SECONDARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(characterService.isChatEligible(character)).thenReturn(false);

        mockMvc.perform(post("/api/characters/character-1/chat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "Who are you?",
                                  "conversationHistory": [],
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", is("Chat is only available for main characters.")))
                .andExpect(jsonPath("$.characterId", is("character-1")));

        verify(chatService, never()).chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void chat_secondaryCharacter_notEligibleWhenBookHasNoPrimary() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.SECONDARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(characterService.isChatEligible(character)).thenReturn(false);

        mockMvc.perform(post("/api/characters/character-1/chat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "Who are you?",
                                  "conversationHistory": [],
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", is("Chat is only available for main characters.")));

        verify(chatService, never()).chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void chat_authenticatedReaderPersistsExchangeAndReturnsSessionId() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);
        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);
        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(characterService.isChatEligible(character)).thenReturn(true);
        when(chatService.chat(
                org.mockito.ArgumentMatchers.eq("character-1"),
                org.mockito.ArgumentMatchers.eq("Who are you?"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn("I am your guide.");
        when(accountAuthService.resolveAuthenticatedPrincipal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new AccountAuthService.AccountPrincipal("user-1", "reader@example.com")));
        when(accountChatHistoryService.recordExchange(
                "user-1", "character-1", "Who are you?", "I am your guide.", 0, 2))
                .thenReturn("session-1");

        mockMvc.perform(post("/api/characters/character-1/chat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "Who are you?",
                                  "conversationHistory": [],
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", is("I am your guide.")))
                .andExpect(jsonPath("$.sessionId", is("session-1")));

        verify(accountChatHistoryService).recordExchange(
                "user-1", "character-1", "Who are you?", "I am your guide.", 0, 2);
    }

    @Test
    void getStatus_includesVoiceCallFields() throws Exception {
        when(voiceCallService.isVoiceCallEnabled()).thenReturn(true);
        when(voiceCallService.isVoiceCallAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/characters/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceCallEnabled", is(true)))
                .andExpect(jsonPath("$.voiceCallAvailable", is(true)));
    }

    @Test
    void callSession_primaryCharacter_returnsSession() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(voiceCallService.isVoiceCallAvailable()).thenReturn(true);
        when(voiceCallService.createSession(
                org.mockito.ArgumentMatchers.eq("character-1"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new CharacterVoiceCallService.VoiceCallSession(
                        "secret-token", 1234567890L, "grok-voice-think-fast-2.0",
                        "wss://api.x.ai/v1/realtime?model=grok-voice-think-fast-2.0",
                        new CharacterVoiceCallService.SessionConfig(
                                "instructions here", "leo",
                                new CharacterVoiceCallService.TurnDetection("server_vad", 0.5, 600, 30000))));

        mockMvc.perform(post("/api/characters/character-1/call-session")
                        .contentType("application/json")
                        .content("""
                                {
                                  "conversationHistory": [{
                                    "role": "user",
                                    "content": "Call me Ishmael.",
                                    "timestamp": "2026-07-23T16:07:54.805411Z"
                                  }],
                                  "readerChapterIndex": 2,
                                  "readerParagraphIndex": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", is("secret-token")))
                .andExpect(jsonPath("$.model", is("grok-voice-think-fast-2.0")))
                .andExpect(jsonPath("$.websocketUrl", is("wss://api.x.ai/v1/realtime?model=grok-voice-think-fast-2.0")))
                .andExpect(jsonPath("$.sessionConfig.voice", is("leo")))
                .andExpect(jsonPath("$.sessionConfig.turnDetection.type", is("server_vad")));

        verify(voiceCallService).createSession(
                org.mockito.ArgumentMatchers.eq("character-1"),
                org.mockito.ArgumentMatchers.argThat(history -> history.size() == 1
                        && history.getFirst().timestamp()
                        == Instant.parse("2026-07-23T16:07:54.805411Z").toEpochMilli()),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    void callSession_secondaryCharacter_returnsForbidden() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.SECONDARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(voiceCallService.isVoiceCallAvailable()).thenReturn(true);

        mockMvc.perform(post("/api/characters/character-1/call-session")
                        .contentType("application/json")
                        .content("""
                                {"conversationHistory": [], "readerChapterIndex": 0, "readerParagraphIndex": 0}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void callSession_voiceUnavailable_returnsForbidden() throws Exception {
        when(voiceCallService.isVoiceCallAvailable()).thenReturn(false);

        mockMvc.perform(post("/api/characters/character-1/call-session")
                        .contentType("application/json")
                        .content("""
                                {"conversationHistory": [], "readerChapterIndex": 0, "readerParagraphIndex": 0}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void callSession_unknownCharacter_returnsNotFound() throws Exception {
        when(voiceCallService.isVoiceCallAvailable()).thenReturn(true);
        when(characterService.getCharacter("character-missing")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/characters/character-missing/call-session")
                        .contentType("application/json")
                        .content("""
                                {"conversationHistory": [], "readerChapterIndex": 0, "readerParagraphIndex": 0}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void callSession_mintFailure_returnsServiceUnavailable() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(voiceCallService.isVoiceCallAvailable()).thenReturn(true);
        when(voiceCallService.createSession(
                org.mockito.ArgumentMatchers.eq("character-1"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new LlmProviderException("mint failed"));

        mockMvc.perform(post("/api/characters/character-1/call-session")
                        .contentType("application/json")
                        .content("""
                                {"conversationHistory": [], "readerChapterIndex": 0, "readerParagraphIndex": 0}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("Voice calls are unavailable right now.")));
    }

    @Test
    void callSession_characterRemovedBetweenCheckAndLoad_returnsNotFound() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setId("character-1");
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(voiceCallService.isVoiceCallAvailable()).thenReturn(true);
        when(voiceCallService.createSession(
                org.mockito.ArgumentMatchers.eq("character-1"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalArgumentException("Character not found: character-1"));

        mockMvc.perform(post("/api/characters/character-1/call-session")
                        .contentType("application/json")
                        .content("""
                                {"conversationHistory": [], "readerChapterIndex": 0, "readerParagraphIndex": 0}
                                """))
                .andExpect(status().isNotFound());
    }
}
