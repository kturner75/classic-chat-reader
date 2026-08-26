package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CharacterController.class)
@TestPropertySource(properties = {
        "generation.cache-only=false",
        "character.enabled=true",
        "ai.chat.enabled=true",
        "illustration.allow-prompt-editing=false"
})
class CharacterControllerPromptEditingTest {

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
    void regeneratePortrait_promptEditingDisabled_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/characters/character-1/portrait/regenerate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "prompt": "Elizabeth Bennet in a pale muslin gown"
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(characterService, never()).regeneratePortraitWithPrompt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void requestPortrait_promptEditingDisabled_stillQueuesGeneration() throws Exception {
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
    }
}
