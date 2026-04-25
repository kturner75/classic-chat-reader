package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.reasoning.ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${ai.reasoning.ollama.model}")
    private String ollamaModel;

    @Value("${character.prefetch.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    private WebClient webClient;

    public CharacterPrefetchService(
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            CharacterRepository characterRepository,
            ParagraphRepository paragraphRepository,
            CharacterService characterService) {
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.characterRepository = characterRepository;
        this.paragraphRepository = paragraphRepository;
        this.characterService = characterService;
    }

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
        log.info("Character prefetch service initialized");
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

        log.info("Starting character prefetch for '{}' by {}", book.getTitle(), book.getAuthor());

        List<PrefetchedCharacter> mainCharacters = queryMainCharacters(book.getTitle(), book.getAuthor());

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

    private List<PrefetchedCharacter> queryMainCharacters(String title, String author) {
        String prompt = buildPrefetchPrompt(title, author);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", ollamaModel,
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of("temperature", 0.2)  // Low temperature for factual responses
            );

            String response = webClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            JsonNode responseNode = objectMapper.readTree(response);
            String generatedText = responseNode.get("response").asText();

            String json = extractJsonArray(generatedText);
            return parseCharacters(json);

        } catch (WebClientResponseException e) {
            log.error("Ollama API error during character prefetch: {} - {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return List.of();
        } catch (Exception e) {
            log.error("Failed to prefetch characters for '{}' by {}", title, author, e);
            return List.of();
        }
    }

    private String buildPrefetchPrompt(String title, String author) {
        return String.format("""
            You are analyzing the famous book "%s" by %s.

            List the MAIN CHARACTERS (typically 3-8 characters who are central to the story).
            For each character, provide:
            1. Their exact name as it appears in the book (use their most common form, e.g., "Elizabeth Bennet" not just "Lizzy")
            2. A 2-3 sentence description of who they are and their role in the story (avoid major spoilers)
            3. The chapter number where they first appear (use 1 if you're unsure or they appear in chapter 1)

            IMPORTANT RULES:
            - Only include major characters who play significant roles throughout the story
            - Do NOT include minor characters who appear briefly
            - Use the character's primary/full name
            - If you don't know this book well, respond with an empty array []
            - Do NOT make up characters - only include characters you're confident about

            Respond ONLY with valid JSON in this exact format:
            [
              {
                "name": "Character Name",
                "description": "Description of the character and their role",
                "firstChapterNumber": 1
              }
            ]

            If you're unfamiliar with this book or unsure about its characters, respond with: []
            """, title, author);
    }

    private List<PrefetchedCharacter> parseCharacters(String json) {
        List<PrefetchedCharacter> characters = new ArrayList<>();
        try {
            JsonNode charactersArray = objectMapper.readTree(json);
            for (JsonNode charNode : charactersArray) {
                String name = charNode.has("name") ? charNode.get("name").asText() : "";
                String description = charNode.has("description")
                        ? charNode.get("description").asText()
                        : "A main character in the story";
                int chapterNumber = charNode.has("firstChapterNumber")
                        ? charNode.get("firstChapterNumber").asInt(1)
                        : 1;

                if (!name.isBlank()) {
                    characters.add(new PrefetchedCharacter(name, description, chapterNumber));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse prefetched characters JSON: {}", json, e);
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
        // If no array found, check if it's an empty response
        if (text.trim().equalsIgnoreCase("[]") || text.toLowerCase().contains("unfamiliar") ||
                text.toLowerCase().contains("don't know")) {
            return "[]";
        }
        log.warn("No JSON array found in prefetch response, returning empty: {}",
                text.length() > 200 ? text.substring(0, 200) + "..." : text);
        return "[]";
    }
}
