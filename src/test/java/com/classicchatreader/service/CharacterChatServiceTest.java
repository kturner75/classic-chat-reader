package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        characterChatService = new CharacterChatService(llmProvider, characterRepository, chapterRepository,
                new CharacterPersonaPromptBuilder());
        ReflectionTestUtils.setField(characterChatService, "maxContextMessages", 10);
    }

    @Test
    void isChatProviderAvailable_delegatesToProviderAvailability() {
        when(llmProvider.isAvailable()).thenReturn(true);
        assertTrue(characterChatService.isChatProviderAvailable());
        verify(llmProvider).isAvailable();
    }

    @Test
    void chat_sendsSharedConductRulesToTheProvider() {
        stubElizabeth();
        when(llmProvider.generate(anyString(), any(LlmOptions.class))).thenReturn("I am quite well, I thank you.");

        characterChatService.chat("char-elizabeth", "How do you do?", List.of(), 3, 12);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmProvider).generate(promptCaptor.capture(), any(LlmOptions.class));
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains(CharacterPersonaPromptBuilder.CONDUCT_SECTION_HEADING));
        assertTrue(prompt.contains("Mild flirtation"));
        assertTrue(prompt.contains("sexual roleplay"));
        assertTrue(prompt.contains("User: How do you do?"));
    }

    @Test
    void chat_providerFailure_asksTheReaderToTryAgain() {
        stubElizabeth();
        when(llmProvider.generate(anyString(), any(LlmOptions.class)))
                .thenThrow(new RuntimeException("provider refused"));

        String reply = characterChatService.chat("char-elizabeth", "Hello!", List.of(), 3, 12);

        assertEquals("I seem to have lost my train of thought. Would you try that again?", reply);
    }

    private void stubElizabeth() {
        BookEntity book = new BookEntity("Pride and Prejudice", "Jane Austen", "gutenberg");
        book.setId("book-pride");
        CharacterEntity character = new CharacterEntity();
        character.setId("char-elizabeth");
        character.setBook(book);
        character.setName("Elizabeth Bennet");
        character.setDescription("Witty and independent.");
        when(characterRepository.findByIdWithBookAndChapter("char-elizabeth")).thenReturn(Optional.of(character));
        when(chapterRepository.findByBookIdAndChapterIndex("book-pride", 3))
                .thenReturn(Optional.of(new ChapterEntity(3, "Chapter IV")));
    }
}
