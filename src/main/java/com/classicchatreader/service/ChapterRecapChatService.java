package com.classicchatreader.service;

import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChapterRecapChatService {

    private static final Logger log = LoggerFactory.getLogger(ChapterRecapChatService.class);

    private final LlmProvider chatProvider;
    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;

    @Value("${recap.chat.max-context-chapters:3}")
    private int maxContextChapters;

    @Value("${recap.chat.max-context-messages:8}")
    private int maxContextMessages;

    @Value("${recap.chat.max-source-chars:12000}")
    private int maxSourceChars;

    public ChapterRecapChatService(
            @Qualifier("chatLlmProvider") LlmProvider chatProvider,
            ChapterRepository chapterRepository,
            ParagraphRepository paragraphRepository) {
        this.chatProvider = chatProvider;
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        log.info("Chapter recap chat service initialized with provider: {}", chatProvider.getProviderName());
    }

    public boolean isChatProviderAvailable() {
        return chatProvider.isAvailable();
    }

    public String chat(
            String bookId,
            String userMessage,
            List<ChatMessage> conversationHistory,
            int readerChapterIndex) {
        List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(bookId);
        if (chapters.isEmpty()) {
            return "I can't discuss this book yet because chapter context is unavailable.";
        }

        int maxChapterIndex = chapters.get(chapters.size() - 1).getChapterIndex();
        if (readerChapterIndex < 0 || readerChapterIndex > maxChapterIndex) {
            return "I can only discuss chapters within your current reading position.";
        }

        int minChapterIndex = Math.max(0, readerChapterIndex - Math.max(1, maxContextChapters) + 1);
        List<ChapterEntity> contextChapters = chapters.stream()
                .filter(chapter -> chapter.getChapterIndex() >= minChapterIndex
                        && chapter.getChapterIndex() <= readerChapterIndex)
                .toList();

        String chapterContext = buildChapterContext(contextChapters);
        String conversationContext = buildConversationContext(conversationHistory);

        String prompt = String.format("""
            You are a spoiler-safe reading companion for this book.

            RULES:
            - The reader is currently at chapter index %d.
            - You may only discuss story details from chapter index <= %d.
            - If asked about later chapters or uncertain facts, say you cannot answer yet.
            - Keep responses concise and grounded in the provided SOURCE CONTEXT only.
            - Do not infer, guess, or use outside knowledge about the book.
            - If SOURCE CONTEXT does not directly support an answer, say so directly.
            - Never reveal or hint at events beyond chapter index %d.

            SOURCE CONTEXT:
            %s

            CONVERSATION:
            %s

            Reader: %s
            Assistant:""",
                readerChapterIndex,
                readerChapterIndex,
                readerChapterIndex,
                chapterContext,
                conversationContext,
                userMessage
        );

        try {
            String generated = chatProvider.generate(prompt, LlmOptions.withTemperatureAndTopP(0.4, 0.9));
            String cleaned = cleanResponse(generated);
            if (cleaned.isBlank()) {
                return "I don't have enough context to answer confidently yet.";
            }
            return cleaned;
        } catch (Exception e) {
            log.error("Failed to generate chapter recap chat response for book {}", bookId, e);
            return "I can't answer right now, but you can continue reading and ask again.";
        }
    }

    private String buildChapterContext(List<ChapterEntity> chapters) {
        StringBuilder context = new StringBuilder();
        int usedChars = 0;

        for (ChapterEntity chapter : chapters) {
            if (usedChars >= maxSourceChars) break;

            List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapter.getId());
            List<String> snippets = paragraphs.stream()
                    .map(p -> p.getContent() == null ? "" : p.getContent().trim())
                    .filter(s -> !s.isBlank())
                    .limit(3)
                    .map(this::trimToLength)
                    .toList();

            String chapterBlock = "Chapter " + (chapter.getChapterIndex() + 1) + " (" + chapter.getTitle() + "):\n"
                    + snippets.stream().collect(Collectors.joining("\n- ", "- ", "\n\n"));

            if (usedChars + chapterBlock.length() > maxSourceChars) {
                int remaining = Math.max(0, maxSourceChars - usedChars);
                context.append(chapterBlock, 0, Math.min(remaining, chapterBlock.length()));
                break;
            }

            context.append(chapterBlock);
            usedChars += chapterBlock.length();
        }

        if (context.isEmpty()) {
            return "No chapter text available.";
        }
        return context.toString();
    }

    private String buildConversationContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "(No prior reader messages)";
        }
        List<ChatMessage> readerMessages = history.stream()
                .filter(message -> message != null
                        && !"assistant".equalsIgnoreCase(message.role())
                        && message.content() != null
                        && !message.content().isBlank())
                .toList();

        if (readerMessages.isEmpty()) {
            return "(No prior reader messages)";
        }

        List<ChatMessage> recent = readerMessages.size() > maxContextMessages
                ? readerMessages.subList(readerMessages.size() - maxContextMessages, readerMessages.size())
                : readerMessages;

        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : recent) {
            sb.append("Reader: ").append(message.content().trim()).append("\n");
        }
        return sb.toString();
    }

    private String cleanResponse(String response) {
        if (response == null) return "";
        String cleaned = response.trim();
        if (cleaned.startsWith("Assistant:")) {
            cleaned = cleaned.substring("Assistant:".length()).trim();
        }
        return cleaned;
    }

    private String trimToLength(String value) {
        if (value.length() <= 260) {
            return value;
        }
        return value.substring(0, 260).trim() + "...";
    }
}
