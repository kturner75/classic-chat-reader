package com.classicchatreader.service;

import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterRecapChatServiceTest {

    @Mock
    private LlmProvider llmProvider;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private ParagraphRepository paragraphRepository;

    private ChapterRecapChatService chapterRecapChatService;

    @BeforeEach
    void setUp() {
        chapterRecapChatService = new ChapterRecapChatService(llmProvider, chapterRepository, paragraphRepository);
        ReflectionTestUtils.setField(chapterRecapChatService, "maxContextChapters", 3);
        ReflectionTestUtils.setField(chapterRecapChatService, "maxContextMessages", 8);
        ReflectionTestUtils.setField(chapterRecapChatService, "maxSourceChars", 12000);
    }

    @Test
    void chat_invalidReaderChapter_returnsGuardedFallback() {
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapter));

        String response = chapterRecapChatService.chat("book-1", "What happens later?", List.of(), 5);

        assertTrue(response.contains("only discuss chapters"));
        verify(llmProvider, never()).generate(anyString(), any());
    }

    @Test
    void chat_validReaderChapter_callsProviderAndCleansPrefix() {
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        ParagraphEntity paragraph = new ParagraphEntity(0, "Call me Ishmael. Some years ago...");
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapter));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1")).thenReturn(List.of(paragraph));
        when(llmProvider.generate(anyString(), any())).thenReturn("Assistant: A shipboard narrative begins.");

        String response = chapterRecapChatService.chat("book-1", "What happened?", List.of(), 0);

        assertEquals("A shipboard narrative begins.", response);
        verify(llmProvider).generate(anyString(), any());
    }

    @Test
    void chat_ignoresAssistantHistoryWhenBuildingPrompt() {
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        ParagraphEntity paragraph = new ParagraphEntity(0, "He looked severe and distant at dinner.");
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapter));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1")).thenReturn(List.of(paragraph));
        when(llmProvider.generate(anyString(), any())).thenReturn("Assistant: He appears severe.");

        List<ChatMessage> history = List.of(
                new ChatMessage("assistant", "He dies later in the story.", 1L),
                new ChatMessage("user", "What did we learn about him so far?", 2L)
        );

        chapterRecapChatService.chat("book-1", "Any clues?", history, 0);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmProvider).generate(promptCaptor.capture(), any());
        String prompt = promptCaptor.getValue();

        assertTrue(prompt.contains("Reader: What did we learn about him so far?"));
        assertFalse(prompt.contains("He dies later in the story."));
    }

    @Test
    void isChatProviderAvailable_delegatesToProviderAvailability() {
        when(llmProvider.isAvailable()).thenReturn(true);

        assertTrue(chapterRecapChatService.isChatProviderAvailable());
        verify(llmProvider).isAvailable();
    }
}
