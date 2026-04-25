package com.classicchatreader.service;

import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterChatServiceTest {

    @Mock
    private LlmProvider llmProvider;

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private ChapterRepository chapterRepository;

    private CharacterChatService characterChatService;

    @BeforeEach
    void setUp() {
        when(llmProvider.getProviderName()).thenReturn("test-provider");
        characterChatService = new CharacterChatService(llmProvider, characterRepository, chapterRepository);
    }

    @Test
    void isChatProviderAvailable_delegatesToProviderAvailability() {
        when(llmProvider.isAvailable()).thenReturn(true);
        assertTrue(characterChatService.isChatProviderAvailable());
        verify(llmProvider).isAvailable();
    }
}
