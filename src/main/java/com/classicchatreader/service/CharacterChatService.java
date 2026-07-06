package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CharacterChatService {

    private static final Logger log = LoggerFactory.getLogger(CharacterChatService.class);

    private final LlmProvider chatProvider;
    private final CharacterRepository characterRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterPersonaPromptBuilder personaPromptBuilder;

    @Value("${character.chat.max-context-messages:10}")
    private int maxContextMessages;

    public CharacterChatService(
            @Qualifier("chatLlmProvider") LlmProvider chatProvider,
            CharacterRepository characterRepository,
            ChapterRepository chapterRepository,
            CharacterPersonaPromptBuilder personaPromptBuilder) {
        this.chatProvider = chatProvider;
        this.characterRepository = characterRepository;
        this.chapterRepository = chapterRepository;
        this.personaPromptBuilder = personaPromptBuilder;
        log.info("Character chat service initialized with provider: {}", chatProvider.getProviderName());
    }

    public boolean isChatProviderAvailable() {
        return chatProvider.isAvailable();
    }

    public String chat(String characterId, String userMessage,
                       List<ChatMessage> conversationHistory,
                       int readerChapterIndex, int readerParagraphIndex) {
        Optional<CharacterEntity> characterOpt = characterRepository.findByIdWithBookAndChapter(characterId);
        if (characterOpt.isEmpty()) {
            log.warn("Character not found for chat: {}", characterId);
            return "I'm sorry, I seem to have lost my place in the story...";
        }

        CharacterEntity character = characterOpt.get();
        BookEntity book = character.getBook();

        String chapterTitle = getChapterTitle(book.getId(), readerChapterIndex);

        String systemPrompt = personaPromptBuilder.buildPersona(character, book, readerChapterIndex,
                readerParagraphIndex, chapterTitle);

        String conversationContext = personaPromptBuilder.buildConversationContext(
                conversationHistory, maxContextMessages);

        String fullPrompt = String.format("""
            %s

            %s

            User: %s

            %s:""",
                systemPrompt,
                conversationContext,
                userMessage,
                character.getName());

        try {
            String generatedText = chatProvider.generate(fullPrompt, LlmOptions.withTemperatureAndTopP(0.8, 0.9)).trim();

            generatedText = cleanResponse(generatedText, character.getName());

            log.debug("Generated chat response for '{}': {}", character.getName(),
                    truncateText(generatedText, 100));

            return generatedText;

        } catch (Exception e) {
            log.error("Failed to generate chat response for character '{}'", character.getName(), e);
            return "I... I'm not sure how to answer that. Perhaps we could discuss something else?";
        }
    }

    private String getChapterTitle(String bookId, int chapterIndex) {
        return chapterRepository.findByBookIdAndChapterIndex(bookId, chapterIndex)
                .map(ChapterEntity::getTitle)
                .orElse(null);
    }

    private String cleanResponse(String response, String characterName) {
        response = response.trim();

        if (response.startsWith(characterName + ":")) {
            response = response.substring(characterName.length() + 1).trim();
        }

        if (response.startsWith("\"") && response.endsWith("\"")) {
            response = response.substring(1, response.length() - 1);
        }

        return response;
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
