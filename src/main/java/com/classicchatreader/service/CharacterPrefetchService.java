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
import java.util.regex.Pattern;

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
        int firstChapterNumber
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
            FirstAppearance appearance = findFirstAppearance(bookId, pc.name());
            ChapterEntity chapter = appearance != null
                    ? appearance.chapter()
                    : findChapterByNumber(bookId, pc.firstChapterNumber());
            if (chapter == null) {
                log.warn("Could not find chapter {} for character '{}', skipping", pc.firstChapterNumber(), pc.name());
                continue;
            }
            int paragraphIndex = appearance != null ? appearance.paragraphIndex() : 0;

            // Check for existing character with same name (case-insensitive)
            Optional<CharacterEntity> existing = characterRepository
                    .findByBookIdAndNameIgnoreCase(bookId, pc.name());

            if (existing.isPresent()) {
                // Promote existing SECONDARY character to PRIMARY
                CharacterEntity existingChar = existing.get();
                if (existingChar.getCharacterType() == CharacterType.SECONDARY) {
                    existingChar.setCharacterType(CharacterType.PRIMARY);
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
                // Create new PRIMARY character
                CharacterEntity character = new CharacterEntity(
                        book, pc.name(), pc.description(), chapter, paragraphIndex
                );
                character.setCharacterType(CharacterType.PRIMARY);
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

            FirstAppearance appearance = findFirstAppearance(book.getId(), character.getName());
            ChapterEntity foundChapter = appearance != null
                    ? appearance.chapter()
                    : findChapterByNumber(book.getId(), 1);
            int foundParagraph = appearance != null ? appearance.paragraphIndex() : 0;
            if (character.getFirstChapter().getId().equals(foundChapter.getId()) &&
                    character.getFirstParagraphIndex() == foundParagraph) {
                continue;
            }

            character.setFirstChapter(foundChapter);
            character.setFirstParagraphIndex(foundParagraph);
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

            List the MAIN CHARACTERS — a tight PRIMARY set of named people who are central to the story (typically 3-8).
            %s
            For each character, provide:
            1. Their exact name as it appears in the book (use their most common form, e.g., "Elizabeth Bennet" not just "Lizzy")
            2. A 2-3 sentence description. %s
            3. The chapter number where they first appear (use 1 if you're unsure or they appear in chapter 1)

            IMPORTANT RULES:
            - Only include major characters who play significant roles throughout the story
            - Do NOT include minor characters who appear briefly
            - Use the character's primary/full name
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
                CharacterDiscoveryPromptRules.REJECT_NON_PERSONS,
                CharacterDiscoveryPromptRules.NO_GLITCH_NAMES);
    }

    private List<PrefetchedCharacter> parseCharacters(String json) throws JsonProcessingException {
        List<PrefetchedCharacter> characters = new ArrayList<>();
        JsonNode charactersArray = objectMapper.readTree(json);
        for (JsonNode charNode : charactersArray) {
            String name = charNode.has("name") ? charNode.get("name").asText() : "";
            String description = charNode.has("description")
                    ? charNode.get("description").asText()
                    : "A main character in the story";
            int chapterNumber = charNode.has("firstChapterNumber")
                    ? charNode.get("firstChapterNumber").asInt(1)
                    : 1;

            if (!name.isBlank() && CharacterRosterNameFilter.isClearlyNamed(name)) {
                characters.add(new PrefetchedCharacter(name, description, chapterNumber));
            }
        }
        return characters;
    }

    private ChapterEntity findChapterByNumber(String bookId, int chapterNumber) {
        // Chapter numbers from LLM are 1-indexed, chapterIndex is 0-indexed
        int chapterIndex = Math.max(0, chapterNumber - 1);

        return chapterRepository.findByBookIdAndChapterIndex(bookId, chapterIndex)
                .orElseGet(() -> {
                    // Fallback to first chapter if specified chapter doesn't exist
                    log.debug("Chapter {} not found for book {}, falling back to chapter 1", chapterNumber, bookId);
                    return chapterRepository.findByBookIdAndChapterIndex(bookId, 0).orElse(null);
                });
    }

    private record FirstAppearance(ChapterEntity chapter, int paragraphIndex) {}

    private FirstAppearance findFirstAppearance(String bookId, String characterName) {
        if (characterName == null || characterName.isBlank()) {
            return null;
        }

        Pattern pattern = buildNamePattern(characterName);
        List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(bookId);
        for (ChapterEntity chapter : chapters) {
            List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapter.getId());
            for (ParagraphEntity paragraph : paragraphs) {
                String content = paragraph.getContent();
                if (content != null && pattern.matcher(content).find()) {
                    return new FirstAppearance(chapter, paragraph.getParagraphIndex());
                }
            }
        }
        return null;
    }

    private Pattern buildNamePattern(String name) {
        String trimmed = name.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return Pattern.compile("\\b" + Pattern.quote(trimmed) + "\\b", Pattern.CASE_INSENSITIVE);
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile("\\b" + regex + "\\b", Pattern.CASE_INSENSITIVE);
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
