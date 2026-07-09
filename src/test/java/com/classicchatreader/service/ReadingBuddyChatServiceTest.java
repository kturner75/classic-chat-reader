package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBuddyChatServiceTest {

    @Mock
    private LlmProvider chatProvider;

    @Mock
    private ReadingBuddyPromptBuilder promptBuilder;

    @Mock
    private ReadingBuddyMemoryService memoryService;

    @Mock
    private ReadingBuddyMetricsService metricsService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ChapterRepository chapterRepository;

    private ReadingBuddyProperties properties;
    private ReadingBuddyPersonaCatalog catalog;
    private ReadingBuddyChatService chatService;

    @BeforeEach
    void setUp() {
        properties = new ReadingBuddyProperties();
        properties.setUserMessageMaxChars(2000);
        catalog = new ReadingBuddyPersonaCatalog(properties);
        when(chatProvider.getProviderName()).thenReturn("mock");
        chatService = new ReadingBuddyChatService(
                chatProvider,
                promptBuilder,
                memoryService,
                catalog,
                properties,
                metricsService,
                bookRepository,
                chapterRepository
        );
    }

    @Test
    void chat_buildsPromptFromServerMemoryOnly_andPersistsTurn() {
        BookEntity book = new BookEntity("Pride and Prejudice", "Jane Austen", "manual");
        book.setId("book-1");
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));

        ChapterEntity chapter = new ChapterEntity(3, "Chapter IV");
        when(chapterRepository.findByBookIdAndChapterIndex("book-1", 3)).thenReturn(Optional.of(chapter));

        List<ReadingBuddyPositionedMessage> recent = List.of(
                new ReadingBuddyPositionedMessage("user", "prior", "chat", 2, 0)
        );
        when(memoryService.loadRecentMessagesForPrompt("owner-A", "book-1", "humorist", 3, 12))
                .thenReturn(recent);
        when(memoryService.getMemorySnapshot("owner-A", "book-1", "humorist"))
                .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot("", null, null, 0, null));
        when(promptBuilder.buildChatPromptForPosition(
                any(), eq("book-1"), eq("Pride and Prejudice"), eq("Jane Austen"),
                eq(3), eq("Chapter IV"), eq(12),
                eq(""), eq(null), eq(null), eq(recent), eq("Does he get worse?")))
                .thenReturn("PROMPT");
        when(chatProvider.generate(eq("PROMPT"), any(LlmOptions.class)))
                .thenReturn("Buddy: Not from what you've read so far.");

        ReadingBuddyMessageEntity userMsg = message("u1", "user", "Does he get worse?");
        ReadingBuddyMessageEntity buddyMsg = message("b1", "buddy", "Not from what you've read so far.");
        when(memoryService.persistChatTurn(
                eq("owner-A"), eq("book-1"), eq("humorist"),
                eq("Does he get worse?"), eq("Not from what you've read so far."),
                eq(3), eq(12)))
                .thenReturn(new ReadingBuddyMemoryService.ChatTurn(userMsg, buddyMsg));

        ReadingBuddyChatService.ChatResult result = chatService.chat(
                "owner-A", "book-1", "humorist", "Does he get worse?", 3, 12);

        assertEquals("Not from what you've read so far.", result.response());
        assertEquals("humorist", result.personaId());
        assertEquals("b1", result.messageId());
        assertEquals("u1", result.userMessageId());

        verify(metricsService).recordChatRequest();
        verify(metricsService).recordChatLatency(org.mockito.ArgumentMatchers.anyLong());
        verify(memoryService).loadRecentMessagesForPrompt("owner-A", "book-1", "humorist", 3, 12);
        // Client history is never a parameter — only server memory is loaded.
        verify(promptBuilder).buildChatPromptForPosition(
                any(), eq("book-1"), anyString(), anyString(),
                eq(3), any(), eq(12),
                any(), any(), any(), eq(recent), eq("Does he get worse?"));
    }

    @Test
    void chat_unknownPersona_throwsValidation() {
        assertThrows(ReadingBuddyChatService.ValidationException.class, () ->
                chatService.chat("owner-A", "book-1", "nope", "hi", 0, 0));
        verify(bookRepository, never()).findById(any());
        verify(metricsService, never()).recordChatRequest();
    }

    @Test
    void chat_blankMessage_throwsValidation() {
        ReadingBuddyChatService.ValidationException ex = assertThrows(
                ReadingBuddyChatService.ValidationException.class,
                () -> chatService.chat("owner-A", "book-1", "humorist", "  ", 0, 0));
        assertEquals("BLANK_MESSAGE", ex.getErrorCode());
    }

    @Test
    void chat_messageTooLong_throwsValidation() {
        properties.setUserMessageMaxChars(10);
        chatService = new ReadingBuddyChatService(
                chatProvider, promptBuilder, memoryService, catalog, properties,
                metricsService, bookRepository, chapterRepository);

        ReadingBuddyChatService.ValidationException ex = assertThrows(
                ReadingBuddyChatService.ValidationException.class,
                () -> chatService.chat("owner-A", "book-1", "humorist", "01234567890", 0, 0));
        assertEquals("MESSAGE_TOO_LONG", ex.getErrorCode());
    }

    @Test
    void chat_invalidPosition_throwsValidation() {
        ReadingBuddyChatService.ValidationException ex = assertThrows(
                ReadingBuddyChatService.ValidationException.class,
                () -> chatService.chat("owner-A", "book-1", "humorist", "hi", -1, 0));
        assertEquals("INVALID_POSITION", ex.getErrorCode());
    }

    @Test
    void chat_bookMissing_throwsNotFound() {
        when(bookRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(ReadingBuddyChatService.BookNotFoundException.class, () ->
                chatService.chat("owner-A", "missing", "humorist", "hi", 0, 0));
    }

    @Test
    void chat_llmFailure_recordsFailedAndStillPersistsFallback() {
        BookEntity book = new BookEntity("Title", "Author", "manual");
        book.setId("book-1");
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(chapterRepository.findByBookIdAndChapterIndex(any(), anyInt())).thenReturn(Optional.empty());
        when(memoryService.loadRecentMessagesForPrompt(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(memoryService.getMemorySnapshot(any(), any(), any()))
                .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot("", null, null, 0, null));
        when(promptBuilder.buildChatPromptForPosition(
                any(), any(), any(), any(), anyInt(), any(), anyInt(),
                any(), any(), any(), any(), any()))
                .thenReturn("PROMPT");
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenThrow(new RuntimeException("provider down"));

        ReadingBuddyMessageEntity userMsg = message("u1", "user", "hi");
        ReadingBuddyMessageEntity buddyMsg = message("b1", "buddy", "fallback");
        when(memoryService.persistChatTurn(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    String buddyContent = invocation.getArgument(4);
                    buddyMsg.setContent(buddyContent);
                    return new ReadingBuddyMemoryService.ChatTurn(userMsg, buddyMsg);
                });

        ReadingBuddyChatService.ChatResult result =
                chatService.chat("owner-A", "book-1", "humorist", "hi", 0, 0);

        assertTrue(result.response().contains("can't answer"));
        verify(metricsService).recordChatFailed();
        verify(memoryService).persistChatTurn(
                eq("owner-A"), eq("book-1"), eq("humorist"),
                eq("hi"), anyString(), eq(0), eq(0));
    }

    @Test
    void softTruncateWords_cutsAtLimit() {
        String many = String.join(" ", java.util.Collections.nCopies(10, "word"));
        String truncated = ReadingBuddyChatService.softTruncateWords(many, 3);
        assertEquals("word word word…", truncated);
    }

    @Test
    void cleanResponse_stripsPersonaPrefix() {
        assertEquals(
                "hello there",
                ReadingBuddyChatService.cleanResponse("The Peanut Gallery: hello there", "The Peanut Gallery"));
    }

    private static ReadingBuddyMessageEntity message(String id, String role, String content) {
        ReadingBuddyMessageEntity entity = new ReadingBuddyMessageEntity();
        entity.setId(id);
        entity.setRole(role);
        entity.setKind("chat");
        entity.setContent(content);
        entity.setContentHash(ReadingBuddyMessageEntity.computeContentHash(role, "chat", content));
        return entity;
    }
}
