package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ChapterEntity;
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
        "ai.chat.enabled=true"
})
class CharacterControllerTest {

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
}
