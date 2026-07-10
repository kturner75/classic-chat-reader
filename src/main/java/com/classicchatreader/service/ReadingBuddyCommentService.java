package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import com.classicchatreader.service.llm.LlmProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proactive decide-or-comment path: hard filters via {@link ReadingBuddyTriggerPolicy},
 * then LLM with {@code COMMENT:} / {@code NONE:} grammar (fail closed).
 * <p>
 * LLM generate runs outside a write transaction; only
 * {@link ReadingBuddyMemoryService#persistProactiveComment} is transactional.
 */
@Service
public class ReadingBuddyCommentService {

    private static final Logger log = LoggerFactory.getLogger(ReadingBuddyCommentService.class);

    private static final Pattern COMMENT_LINE = Pattern.compile(
            "(?im)^\\s*COMMENT:\\s*(.+?)\\s*$");
    private static final Pattern NONE_LINE = Pattern.compile(
            "(?im)^\\s*NONE:\\s*(.*?)\\s*$");

    private final LlmProvider chatProvider;
    private final ReadingBuddyTriggerPolicy triggerPolicy;
    private final ReadingBuddyPromptBuilder promptBuilder;
    private final ReadingBuddyMemoryService memoryService;
    private final ReadingBuddyPersonaCatalog personaCatalog;
    private final ReadingBuddyPreferenceService preferenceService;
    private final ReadingBuddyProperties properties;
    private final ReadingBuddyMetricsService metricsService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;

    public ReadingBuddyCommentService(
            @Qualifier("chatLlmProvider") LlmProvider chatProvider,
            ReadingBuddyTriggerPolicy triggerPolicy,
            ReadingBuddyPromptBuilder promptBuilder,
            ReadingBuddyMemoryService memoryService,
            ReadingBuddyPersonaCatalog personaCatalog,
            ReadingBuddyPreferenceService preferenceService,
            ReadingBuddyProperties properties,
            ReadingBuddyMetricsService metricsService,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            ParagraphRepository paragraphRepository) {
        this.chatProvider = chatProvider;
        this.triggerPolicy = triggerPolicy;
        this.promptBuilder = promptBuilder;
        this.memoryService = memoryService;
        this.personaCatalog = personaCatalog;
        this.preferenceService = preferenceService;
        this.properties = properties;
        this.metricsService = metricsService;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        log.info("Reading buddy comment service initialized with provider: {}", chatProvider.getProviderName());
    }

    /**
     * Hard-filter then optionally LLM. {@code clientHint} is advisory only and ignored for decisions.
     */
    public CheckCommentResult checkComment(
            String ownerKey,
            String bookId,
            String personaId,
            int readerChapterIndex,
            int readerParagraphIndex,
            ClientHint clientHint) {
        // clientHint is advisory — never trust for server gaps/cooldowns.
        ReadingBuddyPersona persona = resolvePersona(personaId);
        validatePositionBasics(bookId, readerChapterIndex, readerParagraphIndex);

        BookEntity book = bookRepository.findById(bookId.trim())
                .orElseThrow(() -> new BookNotFoundException(bookId));

        ChapterEntity chapter = chapterRepository
                .findByBookIdAndChapterIndex(book.getId(), readerChapterIndex)
                .orElseThrow(() -> new ValidationException(
                        "INVALID_POSITION",
                        "Chapter index out of range for book: " + readerChapterIndex));
        if (!paragraphRepository.existsByChapterIdAndParagraphIndex(chapter.getId(), readerParagraphIndex)) {
            throw new ValidationException(
                    "INVALID_POSITION",
                    "Paragraph index out of range for chapter: " + readerParagraphIndex);
        }

        ReadingBuddyPreferenceService.EffectivePreferences prefs =
                preferenceService.getEffective(ownerKey, book.getId());

        // Prefer request persona when valid; prefs still drive enabled/frequency/suppress.
        String effectivePersonaId = persona.id();
        String frequency = prefs.frequency() == null ? "rare" : prefs.frequency();

        ReadingBuddyTriggerPolicy.TriggerContext triggerContext =
                new ReadingBuddyTriggerPolicy.TriggerContext(
                        ownerKey,
                        book.getId(),
                        effectivePersonaId,
                        readerChapterIndex,
                        readerParagraphIndex,
                        prefs.enabled(),
                        frequency,
                        prefs.suppressUntilEpochMs()
                );

        long started = System.currentTimeMillis();
        metricsService.recordCheckTotal();
        try {
            ReadingBuddyTriggerPolicy.TriggerDecision decision = triggerPolicy.evaluate(triggerContext);
            if (decision instanceof ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence) {
                metricsService.recordCheckSilence();
                return CheckCommentResult.silence(
                        silence.reason(),
                        silence.nextEligibleAfterMs(),
                        effectivePersonaId,
                        readerChapterIndex,
                        readerParagraphIndex);
            }

            // Eligible — call LLM decide-or-comment
            ReadingBuddyMemoryService.MemorySnapshot memory =
                    memoryService.getMemorySnapshot(ownerKey, book.getId(), effectivePersonaId);

            String prompt = promptBuilder.buildProactivePromptForPosition(
                    persona,
                    book.getId(),
                    book.getTitle(),
                    book.getAuthor(),
                    readerChapterIndex,
                    chapter.getTitle(),
                    readerParagraphIndex,
                    memory.summaryText(),
                    memory.summaryMaxChapterIndex(),
                    memory.summaryMaxParagraphIndex());

            String generated;
            try {
                double temperature = persona.temperature() > 0 ? persona.temperature() : 0.6;
                generated = chatProvider.generate(
                        prompt,
                        LlmOptions.withTemperatureAndTopP(temperature, 0.9));
            } catch (Exception e) {
                metricsService.recordCheckFailed();
                if (LlmProviderException.isTransient(e)) {
                    log.warn(
                            "event=buddy_check_failed bookId={} personaId={} ownerKey={} errorType={} errorMessage={}",
                            book.getId(),
                            effectivePersonaId,
                            truncateForLog(ownerKey, 40),
                            e.getClass().getSimpleName(),
                            e.getMessage()
                    );
                } else {
                    log.error(
                            "event=buddy_check_failed bookId={} personaId={} ownerKey={} errorType={} errorMessage={}",
                            book.getId(),
                            effectivePersonaId,
                            truncateForLog(ownerKey, 40),
                            e.getClass().getSimpleName(),
                            e.getMessage(),
                            e
                    );
                }
                // Distinct from DECIDED_NONE so clients can retry sooner after outages.
                long nextMs = Math.min(
                        30_000L,
                        Math.max(5_000L, properties.minCooldownMsFor(frequency) / 6));
                return CheckCommentResult.silence(
                        ReadingBuddyTriggerPolicy.SilenceReason.PROVIDER_ERROR,
                        nextMs,
                        effectivePersonaId,
                        readerChapterIndex,
                        readerParagraphIndex);
            }

            ParsedDecision parsed = parseLlmDecision(generated);
            if (parsed.action() != ParsedAction.COMMENT) {
                metricsService.recordCheckSilence();
                long nextMs = Math.max(0L, properties.minCooldownMsFor(frequency));
                return CheckCommentResult.silence(
                        ReadingBuddyTriggerPolicy.SilenceReason.DECIDED_NONE,
                        nextMs,
                        effectivePersonaId,
                        readerChapterIndex,
                        readerParagraphIndex);
            }

            int maxWords = resolveMaxProactiveWords(persona);
            String truncated = hardTruncateWords(parsed.text(), maxWords);
            if (truncated.isBlank()) {
                metricsService.recordCheckSilence();
                long nextMs = Math.max(0L, properties.minCooldownMsFor(frequency));
                return CheckCommentResult.silence(
                        ReadingBuddyTriggerPolicy.SilenceReason.DECIDED_NONE,
                        nextMs,
                        effectivePersonaId,
                        readerChapterIndex,
                        readerParagraphIndex);
            }

            // Narrow REQUIRES_NEW write inside memoryService; race → keep first row.
            ReadingBuddyMemoryService.ProactivePersistResult persistResult =
                    memoryService.persistProactiveComment(
                            ownerKey,
                            book.getId(),
                            effectivePersonaId,
                            truncated,
                            readerChapterIndex,
                            readerParagraphIndex);

            ReadingBuddyMessageEntity saved = persistResult.message();
            long nextEligible = decision.nextEligibleAfterMs();
            if (nextEligible <= 0) {
                nextEligible = Math.max(0L, properties.minCooldownMsFor(frequency));
            }

            if (!persistResult.inserted()) {
                // Concurrent winner already stored — do not double-count COMMENT metrics.
                // Still return the first row so the client can show the same toast text.
                metricsService.recordCheckSilence();
                log.debug(
                        "event=buddy_check_race_existing bookId={} personaId={} chapter={} paragraph={} messageId={}",
                        book.getId(),
                        effectivePersonaId,
                        readerChapterIndex,
                        readerParagraphIndex,
                        saved.getId()
                );
                return CheckCommentResult.comment(
                        saved.getId(),
                        saved.getContent(),
                        effectivePersonaId,
                        persona.portraitPath(),
                        readerChapterIndex,
                        readerParagraphIndex,
                        nextEligible);
            }

            metricsService.recordCheckComment();
            log.debug(
                    "event=buddy_check_comment bookId={} personaId={} chapter={} paragraph={} chars={}",
                    book.getId(),
                    effectivePersonaId,
                    readerChapterIndex,
                    readerParagraphIndex,
                    truncated.length()
            );

            return CheckCommentResult.comment(
                    saved.getId(),
                    saved.getContent(),
                    effectivePersonaId,
                    persona.portraitPath(),
                    readerChapterIndex,
                    readerParagraphIndex,
                    nextEligible);
        } finally {
            metricsService.recordCheckLatency(System.currentTimeMillis() - started);
        }
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

    private void validatePositionBasics(String bookId, int chapterIndex, int paragraphIndex) {
        if (bookId == null || bookId.isBlank()) {
            throw new ValidationException("INVALID_BOOK_ID", "bookId is required");
        }
        if (chapterIndex < 0 || paragraphIndex < 0) {
            throw new ValidationException(
                    "INVALID_POSITION",
                    "readerChapterIndex and readerParagraphIndex must be non-negative");
        }
    }

    private int resolveMaxProactiveWords(ReadingBuddyPersona persona) {
        if (persona.maxProactiveWords() > 0) {
            return persona.maxProactiveWords();
        }
        return Math.max(1, properties.getProactive().getMaxWords());
    }

    /**
     * Parses free-form COMMENT:/NONE: line grammar. Invalid / empty / multi-block ambiguity → NONE.
     */
    static ParsedDecision parseLlmDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedDecision.none("empty");
        }
        String text = raw.trim();

        Matcher commentMatcher = COMMENT_LINE.matcher(text);
        Matcher noneMatcher = NONE_LINE.matcher(text);

        boolean hasComment = commentMatcher.find();
        boolean hasNone = noneMatcher.find();

        // Multi-block ambiguity (both present) → fail closed as NONE
        if (hasComment && hasNone) {
            return ParsedDecision.none("ambiguous");
        }
        if (hasNone) {
            String reason = noneMatcher.group(1);
            return ParsedDecision.none(reason == null || reason.isBlank() ? "none" : reason.trim());
        }
        if (hasComment) {
            String body = commentMatcher.group(1);
            if (body == null || body.isBlank()) {
                return ParsedDecision.none("empty_comment");
            }
            // Prefer first COMMENT line only (ignore trailing noise after first match)
            return new ParsedDecision(ParsedAction.COMMENT, body.trim(), null);
        }

        // No recognized grammar → fail closed
        return ParsedDecision.none("invalid");
    }

    /**
     * Hard-truncates by whitespace words; prefers ending on a sentence boundary when possible.
     * No ellipsis (proactive toast should not look cut mid-word with "…").
     * <p>
     * Sentence ends must be followed by whitespace or end-of-string, and short title-style
     * abbreviations ({@code Dr.}, {@code Mr.}) are skipped so we do not cut to {@code "Dr."}.
     */
    static String hardTruncateWords(String text, int maxWords) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || maxWords <= 0) {
            return "";
        }
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
        String cut = sb.toString().trim();
        int minIndex = Math.max(8, cut.length() / 3);
        for (int i = cut.length() - 1; i >= minIndex; i--) {
            char c = cut.charAt(i);
            if (c != '.' && c != '!' && c != '?') {
                continue;
            }
            // Boundary must be end-of-window or followed by whitespace (true sentence end).
            if (i + 1 < cut.length() && !Character.isWhitespace(cut.charAt(i + 1))) {
                continue;
            }
            if (c == '.' && isLikelyAbbreviation(cut, i)) {
                continue;
            }
            // Require a meaningful body before the boundary.
            if (i + 1 < 12) {
                continue;
            }
            return cut.substring(0, i + 1).trim();
        }
        return cut;
    }

    /**
     * True when the word immediately before {@code periodIndex} looks like a short title
     * abbreviation (1–3 letters), e.g. {@code Dr.}, {@code Mr.}, {@code St.}.
     */
    static boolean isLikelyAbbreviation(String text, int periodIndex) {
        if (periodIndex <= 0 || text.charAt(periodIndex) != '.') {
            return false;
        }
        int end = periodIndex - 1;
        if (!Character.isLetter(text.charAt(end))) {
            return false;
        }
        int start = end;
        while (start >= 0 && Character.isLetter(text.charAt(start))) {
            start--;
        }
        int wordLen = end - start;
        return wordLen >= 1 && wordLen <= 3;
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

    enum ParsedAction {
        COMMENT,
        NONE
    }

    record ParsedDecision(ParsedAction action, String text, String noneReason) {
        static ParsedDecision none(String reason) {
            return new ParsedDecision(ParsedAction.NONE, null, reason);
        }
    }

    /**
     * Advisory client fields only — never used for server eligibility.
     */
    public record ClientHint(
            Integer paragraphsSinceLastComment,
            Long dwellMs
    ) {
    }

    public sealed interface CheckCommentResult
            permits CheckCommentResult.Comment, CheckCommentResult.Silence {

        String action();

        String personaId();

        int chapterIndex();

        int paragraphIndex();

        long nextEligibleAfterMs();

        static Comment comment(
                String messageId,
                String text,
                String personaId,
                String portraitUrl,
                int chapterIndex,
                int paragraphIndex,
                long nextEligibleAfterMs) {
            return new Comment(
                    messageId, text, personaId, portraitUrl,
                    chapterIndex, paragraphIndex, nextEligibleAfterMs);
        }

        static Silence silence(
                ReadingBuddyTriggerPolicy.SilenceReason reason,
                long nextEligibleAfterMs,
                String personaId,
                int chapterIndex,
                int paragraphIndex) {
            return new Silence(reason, nextEligibleAfterMs, personaId, chapterIndex, paragraphIndex);
        }

        record Comment(
                String messageId,
                String text,
                String personaId,
                String portraitUrl,
                int chapterIndex,
                int paragraphIndex,
                long nextEligibleAfterMs
        ) implements CheckCommentResult {
            @Override
            public String action() {
                return "COMMENT";
            }
        }

        record Silence(
                ReadingBuddyTriggerPolicy.SilenceReason reason,
                long nextEligibleAfterMs,
                String personaId,
                int chapterIndex,
                int paragraphIndex
        ) implements CheckCommentResult {
            @Override
            public String action() {
                return "SILENCE";
            }
        }
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
