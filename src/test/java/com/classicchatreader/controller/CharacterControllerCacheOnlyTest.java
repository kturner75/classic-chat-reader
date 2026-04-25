package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.CharacterChatService;
import com.classicchatreader.service.CharacterExtractionService;
import com.classicchatreader.service.CharacterPrefetchService;
import com.classicchatreader.service.CharacterService;
import com.classicchatreader.service.ComfyUIService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CharacterController.class)
@TestPropertySource(properties = {
        "generation.cache-only=true",
        "character.enabled=true",
        "ai.chat.enabled=true"
})
class CharacterControllerCacheOnlyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CharacterService characterService;

    @MockitoBean
    private CharacterChatService chatService;

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

    @Test
    void getStatus_cacheOnlyMode_keepsCharacterChatEnabledFlag() throws Exception {
        mockMvc.perform(get("/api/characters/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheOnly", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(true)));
    }

    @Test
    void chat_cacheOnlyMode_allowsChatWhenEnabled() throws Exception {
        BookEntity book = new BookEntity();
        book.setCharacterEnabled(true);

        CharacterEntity character = new CharacterEntity();
        character.setBook(book);
        character.setCharacterType(CharacterType.PRIMARY);

        when(characterService.getCharacter("character-1")).thenReturn(Optional.of(character));
        when(chatService.chat("character-1", "Hello there", java.util.List.of(), 0, 0))
                .thenReturn("Hi there.");

        mockMvc.perform(post("/api/characters/character-1/chat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "Hello there",
                                  "conversationHistory": [],
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", is("Hi there.")));

        verify(characterService).getCharacter("character-1");
        verify(chatService).chat("character-1", "Hello there", java.util.List.of(), 0, 0);
    }
}
