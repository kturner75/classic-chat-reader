package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Interactive reading-buddy chat: server memory for prompts (client history ignored),
 * position-bounded story context, durable turn persistence.
 * <p>
 * Reply LLM generate runs outside any write transaction. {@link ReadingBuddyMemoryService#persistChatTurn}
 * commits the user/buddy rows in an internal {@code REQUIRES_NEW} write, then may run an optional
 * inline summary LLM after that TX commits (summary failures never fail the chat path).
 */
@Service
public class ReadingBuddyChatService {

    private static final Logger log = LoggerFactory.getLogger(ReadingBuddyChatService.class);

    /** Soft hard-cap beyond persona/prompt target (design: soft truncate at 200 words). */
    private static final int CHAT_SOFT_TRUNCATE_WORDS = 200;

    private final LlmProvider chatProvider;
    private final ReadingBuddyPromptBuilder promptBuilder;
    private final ReadingBuddyMemoryService memoryService;
    private final ReadingBuddyPersonaCatalog personaCatalog;
    private final ReadingBuddyProperties properties;
    private final ReadingBuddyMetricsService metricsService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;

    public ReadingBuddyChatService(
            @Qualifier("chatLlmProvider") LlmProvider chatProvider,
            ReadingBuddyPromptBuilder promptBuilder,
            ReadingBuddyMemoryService memoryService,
            ReadingBuddyPersonaCatalog personaCatalog,
            ReadingBuddyProperties properties,
            ReadingBuddyMetricsService metricsService,
            BookRepository bookRepository,
            ChapterRepository chapterRepository) {
        this.chatProvider = chatProvider;
        this.promptBuilder = promptBuilder;
        this.memoryService = memoryService;
        this.personaCatalog = personaCatalog;
        this.properties = properties;
        this.metricsService = metricsService;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        log.info("Reading buddy chat service initialized with provider: {}", chatProvider.getProviderName());
    }

    public boolean isChatProviderAvailable() {
        return chatProvider.isAvailable();
    }

    /**
     * Runs a chat turn. Client {@code conversationHistory} must not be used for prompts —
     * only server-persisted recent messages (position-filtered) are injected.
     * <p>
     * Successful replies are persisted. LLM failure / blank cleaned replies are <em>not</em>
     * written to durable memory (synthetic fallbacks must not pollute conversation context).
     */
    public ChatResult chat(
            String ownerKey,
            String bookId,
            String personaId,
            String message,
            int readerChapterIndex,
            int readerParagraphIndex) {
        ReadingBuddyPersona persona = resolvePersona(personaId);
        validateChatRequest(bookId, message, readerChapterIndex, readerParagraphIndex);

        BookEntity book = bookRepository.findById(bookId.trim())
                .orElseThrow(() -> new BookNotFoundException(bookId));

        String userMessage = message.trim();
        String chapterTitle = chapterRepository
                .findByBookIdAndChapterIndex(book.getId(), readerChapterIndex)
                .map(ChapterEntity::getTitle)
                .orElse(null);

        List<ReadingBuddyPositionedMessage> recentForPrompt = memoryService.loadRecentMessagesForPrompt(
                ownerKey,
                book.getId(),
                persona.id(),
                readerChapterIndex,
                readerParagraphIndex);

        ReadingBuddyMemoryService.MemorySnapshot memory =
                memoryService.getMemorySnapshot(ownerKey, book.getId(), persona.id());

        String prompt = promptBuilder.buildChatPromptForPosition(
                persona,
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                readerChapterIndex,
                chapterTitle,
                readerParagraphIndex,
                memory.summaryText(),
                memory.summaryMaxChapterIndex(),
                memory.summaryMaxParagraphIndex(),
                recentForPrompt,
                userMessage);

        long started = System.currentTimeMillis();
        metricsService.recordChatRequest();
        String reply;
        boolean generationOk;
        try {
            double temperature = persona.temperature() > 0 ? persona.temperature() : 0.7;
            String generated = chatProvider.generate(
                    prompt,
                    LlmOptions.withTemperatureAndTopP(temperature, 0.9));
            reply = softTruncateWords(cleanResponse(generated, persona.displayName()), CHAT_SOFT_TRUNCATE_WORDS);
            if (reply.isBlank()) {
                metricsService.recordChatFailed();
                generationOk = false;
                reply = "I'm not sure how to respond to that just yet — keep reading and ask again anytime.";
            } else {
                generationOk = true;
            }
        } catch (Exception e) {
            metricsService.recordChatFailed();
            generationOk = false;
            log.error(
                    "event=buddy_chat_failed bookId={} personaId={} ownerKey={} errorType={} errorMessage={}",
                    book.getId(),
                    persona.id(),
                    truncateForLog(ownerKey, 40),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e
            );
            reply = "I can't answer right now, but you can keep reading and ask again.";
        } finally {
            metricsService.recordChatLatency(System.currentTimeMillis() - started);
        }

        String messageId = null;
        String userMessageId = null;
        if (generationOk) {
            // Narrow write TX lives inside memoryService.persistChatTurn only.
            ReadingBuddyMemoryService.ChatTurn turn = memoryService.persistChatTurn(
                    ownerKey,
                    book.getId(),
                    persona.id(),
                    userMessage,
                    reply,
                    readerChapterIndex,
                    readerParagraphIndex);
            messageId = turn.buddyMessage().getId();
            userMessageId = turn.userMessage().getId();
        }

        log.debug(
                "event=buddy_chat_generated bookId={} personaId={} latencyMs={} replyChars={} persisted={}",
                book.getId(),
                persona.id(),
                System.currentTimeMillis() - started,
                reply.length(),
                generationOk
        );

        return new ChatResult(
                reply,
                persona.id(),
                messageId,
                userMessageId,
                System.currentTimeMillis()
        );
    }

    private ReadingBuddyPersona resolvePersona(String personaId) {
        if (personaId == null || personaId.isBlank()) {
            throw new ValidationException("UNKNOWN_PERSONA", "personaId is required");
        }
        return personaCatalog.findById(personaId.trim())
                .orElseThrow(() -> new ValidationException(
                        "UNKNOWN_PERSONA",
                        "Unknown personaId: " + personaId));
    }

    private void validateChatRequest(
            String bookId,
            String message,
            int readerChapterIndex,
            int readerParagraphIndex) {
        if (bookId == null || bookId.isBlank()) {
            throw new ValidationException("INVALID_BOOK_ID", "bookId is required");
        }
        if (message == null || message.isBlank()) {
            throw new ValidationException("BLANK_MESSAGE", "Message must not be blank");
        }
        int maxChars = Math.max(1, properties.getUserMessageMaxChars());
        if (message.length() > maxChars) {
            throw new ValidationException(
                    "MESSAGE_TOO_LONG",
                    "Message exceeds " + maxChars + " characters.");
        }
        if (readerChapterIndex < 0 || readerParagraphIndex < 0) {
            throw new ValidationException(
                    "INVALID_POSITION",
                    "readerChapterIndex and readerParagraphIndex must be non-negative");
        }
    }

    static String cleanResponse(String response, String personaDisplayName) {
        if (response == null) {
            return "";
        }
        String cleaned = response.trim();
        if (personaDisplayName != null && !personaDisplayName.isBlank()) {
            String prefix = personaDisplayName + ":";
            if (cleaned.regionMatches(true, 0, prefix, 0, prefix.length())) {
                cleaned = cleaned.substring(prefix.length()).trim();
            }
        }
        if (cleaned.regionMatches(true, 0, "Buddy:", 0, "Buddy:".length())) {
            cleaned = cleaned.substring("Buddy:".length()).trim();
        }
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    /**
     * Soft-truncates by whitespace words; appends ellipsis when cut.
     */
    static String softTruncateWords(String text, int maxWords) {
        if (text == null || text.isBlank() || maxWords <= 0) {
            return text == null ? "" : text.trim();
        }
        String trimmed = text.trim();
        String[] words = trimmed.split("\\s+");
        if (words.length <= maxWords) {
            return trimmed;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.append('…').toString();
    }

    private static String truncateForLog(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    public record ChatResult(
            String response,
            String personaId,
            String messageId,
            String userMessageId,
            long timestamp
    ) {
    }

    public static class BookNotFoundException extends RuntimeException {
        public BookNotFoundException(String bookId) {
            super("Book not found: " + bookId);
        }
    }

    public static class ValidationException extends RuntimeException {
        private final String errorCode;

        public ValidationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode == null ? "INVALID_REQUEST" : errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
