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
import java.util.Locale;
import java.util.Optional;

@Service
public class CharacterPrefetchService {

    private static final Logger log = LoggerFactory.getLogger(CharacterPrefetchService.class);

    static final int DEFAULT_MAX_PROMPT_CHARS = 8000;
    static final int DEFAULT_MAX_CHAPTER_TITLE_CHARS = 60;

    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterRepository characterRepository;
    private final ParagraphRepository paragraphRepository;
    private final CharacterService characterService;
    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    @Value("${character.prefetch.max-prompt-chars:" + DEFAULT_MAX_PROMPT_CHARS + "}")
    private int maxPromptChars;

    @Value("${character.prefetch.max-chapter-title-chars:" + DEFAULT_MAX_CHAPTER_TITLE_CHARS + "}")
    private int maxChapterTitleChars;

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
        Integer firstChapterIndex,
        Integer firstChapterNumber,
        CharacterType characterType
    ) {
        PrefetchedCharacter(String name, String description, Integer firstChapterIndex) {
            this(name, description, firstChapterIndex, null, CharacterType.PRIMARY);
        }
    }

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
            mainCharacters = queryMainCharacters(book);
        } catch (Exception e) {
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
        int moved = 0;

        for (PrefetchedCharacter pc : mainCharacters) {
            FirstAppearance appearance = resolveFirstAppearance(bookId, pc);
            if (appearance == null) {
                // first_chapter_id is NOT NULL; skip rather than invent a placeholder row.
                log.warn("Could not map first appearance for character '{}', skipping", pc.name());
                continue;
            }
            ChapterEntity chapter = appearance.chapter();
            int paragraphIndex = appearance.paragraphIndex();

            Optional<CharacterEntity> existing = characterRepository
                    .findByBookIdAndNameIgnoreCase(bookId, pc.name());

            if (existing.isPresent()) {
                CharacterEntity existingChar = existing.get();
                CharacterType previousType = existingChar.getCharacterType();
                existingChar.setCharacterType(pc.characterType());
                if (previousType == CharacterType.SECONDARY) {
                    existingChar.setFirstChapter(chapter);
                    existingChar.setFirstParagraphIndex(paragraphIndex);
                    if (pc.description() != null && !pc.description().isBlank() &&
                            (existingChar.getDescription() == null ||
                             pc.description().length() > existingChar.getDescription().length())) {
                        existingChar.setDescription(pc.description());
                    }
                    characterRepository.save(existingChar);
                    if (pc.characterType() == CharacterType.PRIMARY) {
                        log.info("Promoted existing character '{}' to PRIMARY", pc.name());
                        promoted++;
                    } else {
                        log.info("Updated existing SECONDARY character '{}'", pc.name());
                    }
                } else if (movePrimaryToEarlierModelChapter(existingChar, bookId, pc)) {
                    moved++;
                } else if (previousType != pc.characterType()) {
                    characterRepository.save(existingChar);
                }
            } else {
                CharacterEntity character = new CharacterEntity(
                        book, pc.name(), pc.description(), chapter, paragraphIndex,
                        pc.characterType()
                );
                characterRepository.save(character);
                characterService.queuePortraitGeneration(character.getId());
                log.info("Created {} character '{}' for book '{}'",
                        pc.characterType(), pc.name(), book.getTitle());
                created++;
            }
        }

        book.setCharacterPrefetchCompleted(true);
        bookRepository.save(book);
        log.info("Character prefetch completed for '{}' - {} created, {} promoted, {} moved earlier",
                book.getTitle(), created, promoted, moved);
    }

    /**
     * Latch-clear + prefetch can correct a PRIMARY pinned too late (exact-phrase
     * scan). Only the model's mapped chapter may move the row, and only earlier.
     * Never overwrite with a later scan hit.
     */
    private boolean movePrimaryToEarlierModelChapter(
            CharacterEntity existing, String bookId, PrefetchedCharacter pc) {
        ChapterEntity modelChapter = mapModelChapter(bookId, pc);
        if (modelChapter == null) {
            return false;
        }
        ChapterEntity stored = existing.getFirstChapter();
        if (stored != null && modelChapter.getChapterIndex() >= stored.getChapterIndex()) {
            return false;
        }
        int previousIndex = stored == null ? -1 : stored.getChapterIndex();
        existing.setFirstChapter(modelChapter);
        existing.setFirstParagraphIndex(0);
        characterRepository.save(existing);
        log.info("Moved PRIMARY '{}' first chapter from {} to model chapter index {}",
                pc.name(), previousIndex, modelChapter.getChapterIndex());
        return true;
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

    private List<PrefetchedCharacter> queryMainCharacters(BookEntity book)
            throws JsonProcessingException {
        List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(book.getId());
        String prompt = buildPrefetchPrompt(book.getTitle(), book.getAuthor(), chapters);
        String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.2));
        return parseCharacters(extractJsonArray(generatedText));
    }

    String buildPrefetchPrompt(String title, String author) {
        return buildPrefetchPrompt(title, author, List.of());
    }

    String buildPrefetchPrompt(String title, String author, List<ChapterEntity> chapters) {
        String chapterMap = buildBoundedChapterMap(chapters);
        String prompt = String.format("""
            You are analyzing the famous book "%s" by %s.

            List the MAIN CHARACTERS: a tight PRIMARY set plus supporting SECONDARY named people worth a roster entry.
            If this work is a novel, include named people who are central to the story (typically 3-8 PRIMARY).
            If this work is a short-story collection or linked tales (title like "The Adventures of Sherlock Holmes", or several distinct stories under one title): include the recurring leads plus the principal named character(s) of each story. Typical set 8-16, not 3-8. Being central to an included story is enough — appearing throughout the book is not required.
            %s
            %s
            For each character, provide:
            1. Their exact name as it appears in the book (use their most common form, e.g., "Elizabeth Bennet" not just "Lizzy")
            2. A 2-3 sentence description. %s
            3. firstChapterIndex: the 0-based chapterIndex from the CHAPTER MAP. %s
            4. characterType: PRIMARY or SECONDARY.

            CHAPTER MAP (stable DB chapterIndex — choose firstChapterIndex exactly from this list):
            %s

            IMPORTANT RULES:
            - For novels: only include major characters who play significant roles throughout the story, plus supporting named people worth portraits
            - For short-story collections / linked tales: include recurring leads plus each story's principal named character(s); "throughout the book" is not required
            - Do NOT include minor walk-ons or unnamed extras
            - Use the character's primary/full name
            - %s
            - %s
            - If you don't know this book well, respond with an empty array []
            - Do NOT make up characters - only include characters you're confident about
            - firstChapterIndex must be one of the chapterIndex values above, or null if unsure

            Respond ONLY with valid JSON in this exact format:
            [
              {
                "name": "Character Name",
                "description": "First-appearance description of the character",
                "firstChapterIndex": 1,
                "characterType": "PRIMARY"
              }
            ]

            If you're unfamiliar with this book or unsure about its characters, respond with: []
            """,
                title,
                author,
                CharacterDiscoveryPromptRules.NAMED_PEOPLE_ONLY,
                CharacterDiscoveryPromptRules.CHARACTER_TYPE_RULE,
                CharacterDiscoveryPromptRules.FIRST_APPEARANCE_BLURB,
                CharacterDiscoveryPromptRules.FIRST_CHAPTER_PLACEMENT,
                chapterMap,
                CharacterDiscoveryPromptRules.REJECT_NON_PERSONS,
                CharacterDiscoveryPromptRules.NO_GLITCH_NAMES);

        if (prompt.length() > maxPromptChars) {
            int overflow = prompt.length() - maxPromptChars;
            String compactMap = compactChapterMap(chapters, Math.max(200, chapterMap.length() - overflow));
            prompt = prompt.replace(chapterMap, compactMap);
            if (prompt.length() > maxPromptChars) {
                prompt = prompt.substring(0, maxPromptChars);
            }
        }
        return prompt;
    }

    String buildBoundedChapterMap(List<ChapterEntity> chapters) {
        return compactChapterMap(chapters, Math.max(400, maxPromptChars / 2));
    }

    private String compactChapterMap(List<ChapterEntity> chapters, int maxChars) {
        if (chapters == null || chapters.isEmpty()) {
            return "(no chapters loaded — return null for firstChapterIndex)";
        }
        String full = formatChapterMapLines(chapters, 0, chapters.size());
        if (full.length() <= maxChars) {
            return full;
        }
        int keepHead = Math.min(40, chapters.size());
        int keepTail = Math.min(10, Math.max(0, chapters.size() - keepHead));
        String compact = formatChapterMapLines(chapters, 0, keepHead)
                + "[... " + (chapters.size() - keepHead - keepTail) + " chapters omitted ...]\n"
                + formatChapterMapLines(chapters, chapters.size() - keepTail, chapters.size());
        if (compact.length() <= maxChars) {
            return compact;
        }
        return truncateForPrompt(compact, maxChars);
    }

    private String formatChapterMapLines(List<ChapterEntity> chapters, int fromInclusive, int toExclusive) {
        StringBuilder map = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive && i < chapters.size(); i++) {
            ChapterEntity chapter = chapters.get(i);
            map.append(chapter.getChapterIndex()).append(". ")
                    .append(truncateForPrompt(chapter.getTitle(), maxChapterTitleChars));
            if (isFrontMatterChapter(chapter)) {
                map.append(" (front matter — mention here is not first presence as a person)");
            }
            map.append('\n');
        }
        return map.toString();
    }

    private static String truncateForPrompt(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    List<PrefetchedCharacter> parseCharacters(String json) throws JsonProcessingException {
        List<PrefetchedCharacter> characters = new ArrayList<>();
        JsonNode charactersArray = objectMapper.readTree(json);
        for (JsonNode charNode : charactersArray) {
            String name = charNode.has("name") ? charNode.get("name").asText() : "";
            String description = charNode.has("description")
                    ? charNode.get("description").asText()
                    : "A main character in the story";
            Integer chapterIndex = parseFirstChapterIndex(charNode);
            Integer chapterNumber = parseFirstChapterNumber(charNode);
            CharacterType characterType = parseCharacterType(charNode);

            if (name.isBlank()) {
                name = charNode.has("characterName") ? charNode.get("characterName").asText() : "";
            }
            if (name.isBlank()) {
                name = charNode.has("fullName") ? charNode.get("fullName").asText() : "";
            }

            if (!name.isBlank() && CharacterRosterNameFilter.isClearlyNamed(name)) {
                characters.add(new PrefetchedCharacter(
                        name.trim(), description, chapterIndex, chapterNumber, characterType));
            } else if (!name.isBlank()) {
                log.info("Dropping prefetch name '{}' — failed roster gate", name);
            }
        }
        return characters;
    }

    private Integer parseFirstChapterIndex(JsonNode charNode) {
        if (!charNode.has("firstChapterIndex") || charNode.get("firstChapterIndex").isNull()) {
            return null;
        }
        JsonNode node = charNode.get("firstChapterIndex");
        if (!node.isNumber() && !(node.isTextual() && !node.asText().isBlank())) {
            return null;
        }
        int value = node.asInt(Integer.MIN_VALUE);
        return value >= 0 ? value : null;
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

    private CharacterType parseCharacterType(JsonNode charNode) {
        if (!charNode.hasNonNull("characterType")) {
            return CharacterType.PRIMARY;
        }
        String raw = charNode.get("characterType").asText("").trim();
        if (raw.isBlank()) {
            return CharacterType.PRIMARY;
        }
        try {
            return CharacterType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.info("Unknown characterType '{}'; defaulting to PRIMARY", raw);
            return CharacterType.PRIMARY;
        }
    }

    /**
     * Prefer an explicit 0-based firstChapterIndex when it maps to a real chapter.
     * Legacy firstChapterNumber is accepted only when it does not land on front matter
     * (so story-chapter 1 is not coerced onto Preface). Phrase scan is fallback only
     * when the model omitted a usable index or the index does not exist.
     */
    private FirstAppearance resolveFirstAppearance(String bookId, PrefetchedCharacter pc) {
        ChapterEntity modelChapter = mapModelChapter(bookId, pc);
        if (modelChapter != null) {
            return new FirstAppearance(modelChapter, 0);
        }
        if (pc.firstChapterIndex() != null) {
            log.info("Model firstChapterIndex {} does not map for '{}'; using phrase-scan fallback",
                    pc.firstChapterIndex(), pc.name());
        } else if (pc.firstChapterNumber() != null) {
            log.info("Model firstChapterNumber {} does not map for '{}'; using phrase-scan fallback",
                    pc.firstChapterNumber(), pc.name());
        } else {
            log.info("Model omitted first chapter for '{}'; using phrase-scan fallback", pc.name());
        }
        return findFirstAppearance(bookId, pc.name());
    }

    private ChapterEntity mapModelChapter(String bookId, PrefetchedCharacter pc) {
        if (pc.firstChapterIndex() != null) {
            return chapterRepository.findByBookIdAndChapterIndex(bookId, pc.firstChapterIndex()).orElse(null);
        }
        if (pc.firstChapterNumber() == null || pc.firstChapterNumber() < 1) {
            return null;
        }
        ChapterEntity legacy = chapterRepository
                .findByBookIdAndChapterIndex(bookId, pc.firstChapterNumber() - 1)
                .orElse(null);
        if (legacy == null) {
            return null;
        }
        // Do not treat "story chapter 1 / unsure" as Preface.
        if (isFrontMatterChapter(legacy)) {
            log.info("Ignoring legacy firstChapterNumber {} for '{}' because it maps to front matter '{}'",
                    pc.firstChapterNumber(), pc.name(), legacy.getTitle());
            return null;
        }
        return legacy;
    }

    static boolean isFrontMatterChapter(ChapterEntity chapter) {
        if (chapter == null || chapter.getTitle() == null) {
            return false;
        }
        return isFrontMatterTitle(chapter.getTitle());
    }

    static boolean isFrontMatterTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String upper = title.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("INTRODUCTION TO ")) {
            return false;
        }
        if (upper.equals("PREFACE") || upper.startsWith("PREFACE.") || upper.startsWith("PREFACE:")
                || upper.startsWith("PREFACE TO ") || upper.contains("'S PREFACE")
                || upper.endsWith(" PREFACE") || upper.contains(" PREFACE TO ")) {
            return true;
        }
        if (upper.equals("INTRODUCTION") || upper.startsWith("INTRODUCTION.") || upper.startsWith("INTRODUCTION:")) {
            return true;
        }
        if (upper.equals("PROLOGUE") || upper.startsWith("PROLOGUE.") || upper.startsWith("PROLOGUE:")
                || upper.startsWith("PROLOGUE ")) {
            return true;
        }
        if (upper.equals("FOREWORD") || upper.startsWith("FOREWORD.") || upper.startsWith("FOREWORD:")) {
            return true;
        }
        return upper.equals("PREFATORY NOTE") || upper.startsWith("PREFATORY NOTE");
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
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        if (text.trim().equalsIgnoreCase("[]") || text.toLowerCase().contains("unfamiliar") ||
                text.toLowerCase().contains("don't know")) {
            return "[]";
        }
        throw new IllegalStateException("No JSON array found in prefetch response: "
                + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
    }
}
