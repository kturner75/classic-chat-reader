package com.classicchatreader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class CharacterPrefetchService {

    private static final Logger log = LoggerFactory.getLogger(CharacterPrefetchService.class);

    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterRepository characterRepository;
    private final ParagraphRepository paragraphRepository;
    private final CharacterService characterService;
    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    public CharacterPrefetchService(
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            CharacterRepository characterRepository,
            ParagraphRepository paragraphRepository,
            CharacterService characterService,
            @Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider) {
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.characterRepository = characterRepository;
        this.paragraphRepository = paragraphRepository;
        this.characterService = characterService;
        this.reasoningProvider = reasoningProvider;
        log.info("Character prefetch service initialized with provider: {}",
                reasoningProvider.getProviderName());
    }

    public record PrefetchedCharacter(
        String name,
        String description,
        Integer firstChapterNumber
    ) {}

    /**
     * Prefetch main characters for a book using LLM knowledge.
     * This should be called asynchronously when a book is first opened.
     */
    @Transactional
    public void prefetchCharactersForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping character prefetch in cache-only mode for book {}", bookId);
            return;
        }
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            log.warn("Book not found for prefetch: {}", bookId);
            return;
        }

        // Check if already prefetched
        if (Boolean.TRUE.equals(book.getCharacterPrefetchCompleted())) {
            log.debug("Characters already prefetched for book: {}", book.getTitle());
            refreshPrimaryCharacterPositions(book);
            return;
        }

        if (!reasoningProvider.isAvailable()) {
            log.warn("Reasoning provider '{}' unavailable; leaving character prefetch incomplete for '{}' so it retries",
                    reasoningProvider.getProviderName(), book.getTitle());
            return;
        }

        log.info("Starting character prefetch for '{}' by {}", book.getTitle(), book.getAuthor());

        List<PrefetchedCharacter> mainCharacters;
        try {
            mainCharacters = queryMainCharacters(book.getTitle(), book.getAuthor());
        } catch (Exception e) {
            // Only a usable answer may mark prefetch done. Latching the flag on an
            // infrastructure failure permanently strands the book with no PRIMARY characters.
            log.error("Character prefetch failed for '{}' by {}; leaving prefetch incomplete so it retries",
                    book.getTitle(), book.getAuthor(), e);
            return;
        }

        if (mainCharacters.isEmpty()) {
            log.info("No main characters returned for '{}' - LLM may not know this book well", book.getTitle());
            book.setCharacterPrefetchCompleted(true);
            bookRepository.save(book);
            return;
        }

        int created = 0;
        int promoted = 0;

        for (PrefetchedCharacter pc : mainCharacters) {
            FirstAppearance appearance = resolveFirstAppearance(bookId, pc);
            if (appearance == null) {
                log.warn("Could not map first appearance for character '{}', skipping", pc.name());
                continue;
            }
            ChapterEntity chapter = appearance.chapter();
            int paragraphIndex = appearance.paragraphIndex();

            // Check for existing character with same name (case-insensitive)
            Optional<CharacterEntity> existing = characterRepository
                    .findByBookIdAndNameIgnoreCase(bookId, pc.name());

            if (existing.isPresent()) {
                // Promote existing SECONDARY character to PRIMARY
                CharacterEntity existingChar = existing.get();
                if (existingChar.getCharacterType() == CharacterType.SECONDARY) {
                    existingChar.setCharacterType(CharacterType.PRIMARY);
                    existingChar.setFirstChapter(chapter);
                    existingChar.setFirstParagraphIndex(paragraphIndex);
                    // Update description if prefetch has a better one
                    if (pc.description() != null && !pc.description().isBlank() &&
                            (existingChar.getDescription() == null ||
                             pc.description().length() > existingChar.getDescription().length())) {
                        existingChar.setDescription(pc.description());
                    }
                    characterRepository.save(existingChar);
                    log.info("Promoted existing character '{}' to PRIMARY", pc.name());
                    promoted++;
                }
            } else {
                // Knowledge prefetch is the PRIMARY list. Type at construction so the
                // entity default (SECONDARY) cannot leak if a later save forgets setType.
                CharacterEntity character = new CharacterEntity(
                        book, pc.name(), pc.description(), chapter, paragraphIndex,
                        CharacterType.PRIMARY
                );
                characterRepository.save(character);

                // Queue portrait generation
                characterService.queuePortraitGeneration(character.getId());
                log.info("Created PRIMARY character '{}' for book '{}'", pc.name(), book.getTitle());
                created++;
            }
        }

        book.setCharacterPrefetchCompleted(true);
        bookRepository.save(book);
        log.info("Character prefetch completed for '{}' - {} created, {} promoted",
                book.getTitle(), created, promoted);
    }

    public int refreshPrimaryCharacterPositionsForBook(String bookId) {
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            log.warn("Book not found for character reindex: {}", bookId);
            return 0;
        }
        return refreshPrimaryCharacterPositions(book);
    }

    public int refreshPrimaryCharacterPositionsForAll() {
        int updated = 0;
        List<BookEntity> books = bookRepository.findAll();
        for (BookEntity book : books) {
            updated += refreshPrimaryCharacterPositions(book);
        }
        return updated;
    }

    private int refreshPrimaryCharacterPositions(BookEntity book) {
        List<CharacterEntity> existingCharacters = characterRepository.findByBookIdOrderByCreatedAt(book.getId());
        int updated = 0;

        for (CharacterEntity character : existingCharacters) {
            if (character.getCharacterType() != CharacterType.PRIMARY) {
                continue;
            }
            // Model placement is the source of truth. Never overwrite an existing
            // first chapter with a later exact-phrase scan hit.
            if (character.getFirstChapter() != null) {
                continue;
            }

            FirstAppearance appearance = findFirstAppearance(book.getId(), character.getName());
            if (appearance == null) {
                continue;
            }

            character.setFirstChapter(appearance.chapter());
            character.setFirstParagraphIndex(appearance.paragraphIndex());
            characterRepository.save(character);
            updated++;
        }

        if (updated > 0) {
            log.info("Refreshed first appearances for {} primary characters in '{}'",
                    updated, book.getTitle());
        }
        return updated;
    }

    /**
     * Returns the main characters the model knows for this book. An empty list means the
     * model does not know the book; any failure throws so the caller can retry later.
     */
    private List<PrefetchedCharacter> queryMainCharacters(String title, String author)
            throws JsonProcessingException {
        String prompt = buildPrefetchPrompt(title, author);
        // Low temperature for factual responses.
        String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.2));
        return parseCharacters(extractJsonArray(generatedText));
    }

    String buildPrefetchPrompt(String title, String author) {
        return String.format("""
            You are analyzing the famous book "%s" by %s.

            List the MAIN CHARACTERS for a tight PRIMARY set.
            If this work is a novel, include named people who are central to the story (typically 3-8).
            If this work is a short-story collection or linked tales (title like "The Adventures of Sherlock Holmes", or several distinct stories under one title): include the recurring leads plus the principal named character(s) of each story. Typical set 8-16, not 3-8. Being central to an included story is enough — appearing throughout the book is not required.
            %s
            For each character, provide:
            1. Their exact name as it appears in the book (use their most common form, e.g., "Elizabeth Bennet" not just "Lizzy")
            2. A 2-3 sentence description. %s
            3. firstChapterNumber: the 1-based story chapter where they are first present as a person in the story (use 1 if you're unsure or they appear in chapter 1). %s

            IMPORTANT RULES:
            - For novels: only include major characters who play significant roles throughout the story
            - For short-story collections / linked tales: include recurring leads plus each story's principal named character(s); "throughout the book" is not required
            - Do NOT include minor walk-ons or unnamed extras
            - Use the character's primary/full name
            - %s
            - %s
            - %s
            - If you don't know this book well, respond with an empty array []
            - Do NOT make up characters - only include characters you're confident about

            Respond ONLY with valid JSON in this exact format:
            [
              {
                "name": "Character Name",
                "description": "First-appearance description of the character",
                "firstChapterNumber": 1
              }
            ]

            If you're unfamiliar with this book or unsure about its characters, respond with: []
            """,
                title,
                author,
                CharacterDiscoveryPromptRules.NAMED_PEOPLE_ONLY,
                CharacterDiscoveryPromptRules.FIRST_APPEARANCE_BLURB,
                CharacterDiscoveryPromptRules.FIRST_CHAPTER_PLACEMENT,
                CharacterDiscoveryPromptRules.REJECT_NON_PERSONS,
                CharacterDiscoveryPromptRules.NO_GLITCH_NAMES,
                CharacterDiscoveryPromptRules.FIRST_CHAPTER_PLACEMENT);
    }

    private List<PrefetchedCharacter> parseCharacters(String json) throws JsonProcessingException {
        List<PrefetchedCharacter> characters = new ArrayList<>();
        JsonNode charactersArray = objectMapper.readTree(json);
        for (JsonNode charNode : charactersArray) {
            String name = charNode.has("name") ? charNode.get("name").asText() : "";
            String description = charNode.has("description")
                    ? charNode.get("description").asText()
                    : "A main character in the story";
            Integer chapterNumber = parseFirstChapterNumber(charNode);

            if (name.isBlank()) {
                name = charNode.has("characterName") ? charNode.get("characterName").asText() : "";
            }
            if (name.isBlank()) {
                name = charNode.has("fullName") ? charNode.get("fullName").asText() : "";
            }

            if (!name.isBlank() && CharacterRosterNameFilter.isClearlyNamed(name)) {
                characters.add(new PrefetchedCharacter(name.trim(), description, chapterNumber));
            } else if (!name.isBlank()) {
                log.info("Dropping prefetch name '{}' — failed roster gate", name);
            }
        }
        return characters;
    }

    private Integer parseFirstChapterNumber(JsonNode charNode) {
        if (!charNode.hasNonNull("firstChapterNumber")) {
            return null;
        }
        JsonNode node = charNode.get("firstChapterNumber");
        if (!node.isNumber() && !(node.isTextual() && !node.asText().isBlank())) {
            return null;
        }
        int value = node.asInt(Integer.MIN_VALUE);
        return value >= 1 ? value : null;
    }

    /**
     * Prefer the model's 1-based chapter when it maps to a real chapter.
     * Phrase scan is fallback only if the model omitted a chapter or the number
     * does not map.
     */
    private FirstAppearance resolveFirstAppearance(String bookId, PrefetchedCharacter pc) {
        ChapterEntity modelChapter = mapModelChapter(bookId, pc.firstChapterNumber());
        if (modelChapter != null) {
            return new FirstAppearance(modelChapter, 0);
        }
        if (pc.firstChapterNumber() != null) {
            log.info("Model firstChapterNumber {} does not map for '{}'; using phrase-scan fallback",
                    pc.firstChapterNumber(), pc.name());
        } else {
            log.info("Model omitted firstChapterNumber for '{}'; using phrase-scan fallback", pc.name());
        }
        return findFirstAppearance(bookId, pc.name());
    }

    private ChapterEntity mapModelChapter(String bookId, Integer chapterNumber) {
        if (chapterNumber == null || chapterNumber < 1) {
            return null;
        }
        return chapterRepository.findByBookIdAndChapterIndex(bookId, chapterNumber - 1).orElse(null);
    }

    private record FirstAppearance(ChapterEntity chapter, int paragraphIndex) {}

    private FirstAppearance findFirstAppearance(String bookId, String characterName) {
        if (characterName == null || characterName.isBlank()) {
            return null;
        }

        List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(bookId);
        for (ChapterEntity chapter : chapters) {
            List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapter.getId());
            for (ParagraphEntity paragraph : paragraphs) {
                if (CharacterRosterNameFilter.appearsInText(characterName, paragraph.getContent())) {
                    return new FirstAppearance(chapter, paragraph.getParagraphIndex());
                }
            }
        }
        return null;
    }

    private String extractJsonArray(String text) {
        // Find JSON array in the response
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        // "I don't know this book" is a real answer and may be recorded as prefetched.
        if (text.trim().equalsIgnoreCase("[]") || text.toLowerCase().contains("unfamiliar") ||
                text.toLowerCase().contains("don't know")) {
            return "[]";
        }
        // Anything else is an unusable response, not an empty cast. Treat it as a failure
        // so the book stays retryable rather than being recorded as having no characters.
        throw new IllegalStateException("No JSON array found in prefetch response: "
                + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
    }
}
