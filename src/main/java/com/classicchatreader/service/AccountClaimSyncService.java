package com.classicchatreader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.entity.ParagraphAnnotationEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.QuizTrophyEntity;
import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.entity.ReadingBuddyPreferenceEntity;
import com.classicchatreader.entity.UserReaderClaimEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.model.AccountStateSnapshot;
import com.classicchatreader.repository.ParagraphAnnotationRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.repository.ReadingBuddyPreferenceRepository;
import com.classicchatreader.repository.UserReaderClaimRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class AccountClaimSyncService {

    static final int MAX_COMPLETED_CHAPTER_INDEXES = 500;
    static final int MAX_CHAPTER_INDEX = 999;

    private final ParagraphAnnotationRepository paragraphAnnotationRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final QuizTrophyRepository quizTrophyRepository;
    private final ReadingBuddyPreferenceRepository readingBuddyPreferenceRepository;
    private final ReadingBuddyMessageRepository readingBuddyMessageRepository;
    private final ReadingBuddyMemoryRepository readingBuddyMemoryRepository;
    private final UserReaderStateRepository userReaderStateRepository;
    private final UserReaderClaimRepository userReaderClaimRepository;
    private final ObjectMapper objectMapper;

    public AccountClaimSyncService(
            ParagraphAnnotationRepository paragraphAnnotationRepository,
            QuizAttemptRepository quizAttemptRepository,
            QuizTrophyRepository quizTrophyRepository,
            ReadingBuddyPreferenceRepository readingBuddyPreferenceRepository,
            ReadingBuddyMessageRepository readingBuddyMessageRepository,
            ReadingBuddyMemoryRepository readingBuddyMemoryRepository,
            UserReaderStateRepository userReaderStateRepository,
            UserReaderClaimRepository userReaderClaimRepository,
            ObjectMapper objectMapper) {
        this.paragraphAnnotationRepository = paragraphAnnotationRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.quizTrophyRepository = quizTrophyRepository;
        this.readingBuddyPreferenceRepository = readingBuddyPreferenceRepository;
        this.readingBuddyMessageRepository = readingBuddyMessageRepository;
        this.readingBuddyMemoryRepository = readingBuddyMemoryRepository;
        this.userReaderStateRepository = userReaderStateRepository;
        this.userReaderClaimRepository = userReaderClaimRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ClaimSyncResult claimAndSync(String userId, String readerId, AccountStateSnapshot incomingState) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }

        boolean claimApplied = claimAnonymousData(userId, readerId);

        AccountStateSnapshot normalizedIncoming = normalize(incomingState);
        AccountStateSnapshot existing = userReaderStateRepository.findById(userId)
                .map(UserReaderStateEntity::getStateJson)
                .map(this::fromJson)
                .orElseGet(AccountStateSnapshot::empty);

        AccountStateSnapshot merged = merge(existing, normalizedIncoming);

        UserReaderStateEntity stateEntity = userReaderStateRepository.findById(userId)
                .orElseGet(UserReaderStateEntity::new);
        stateEntity.setUserId(userId);
        stateEntity.setStateJson(toJson(merged));
        userReaderStateRepository.save(stateEntity);

        return new ClaimSyncResult(claimApplied, merged);
    }

    private boolean claimAnonymousData(String userId, String readerId) {
        if (readerId == null || readerId.isBlank() || readerId.startsWith("user:")) {
            return false;
        }

        if (userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)) {
            return false;
        }

        claimParagraphAnnotations(userId, readerId);
        claimQuizAttempts(userId, readerId);
        claimQuizTrophies(userId, readerId);

        String anonKey = readerId;
        String userKey = "user:" + userId;
        claimReadingBuddyPreferences(anonKey, userKey);
        // deletedAnonMessageId -> surviving user message id (null if dropped without survivor)
        Map<String, String> deletedAnonMessageIds =
                claimReadingBuddyMessages(anonKey, userKey);
        claimReadingBuddyMemories(anonKey, userKey, deletedAnonMessageIds);

        UserReaderClaimEntity claim = new UserReaderClaimEntity();
        claim.setUserId(userId);
        claim.setReaderId(readerId);
        try {
            userReaderClaimRepository.save(claim);
        } catch (DataIntegrityViolationException ignored) {
            // Duplicate claim from concurrent requests is safe and idempotent.
        }
        return true;
    }

    /**
     * Merge anonymous reading-buddy preferences into the account owner key.
     * Global + per-book: last-write-wins by {@code updated_at}; account wins on tie.
     */
    private void claimReadingBuddyPreferences(String anonKey, String userKey) {
        List<ReadingBuddyPreferenceEntity> anonPrefs =
                readingBuddyPreferenceRepository.findByOwnerKey(anonKey);
        if (anonPrefs.isEmpty()) {
            return;
        }

        Map<String, ReadingBuddyPreferenceEntity> userByBook = new HashMap<>();
        for (ReadingBuddyPreferenceEntity userPref : readingBuddyPreferenceRepository.findByOwnerKey(userKey)) {
            userByBook.put(userPref.getBookId(), userPref);
        }

        for (ReadingBuddyPreferenceEntity anon : anonPrefs) {
            ReadingBuddyPreferenceEntity userRow = userByBook.get(anon.getBookId());
            if (userRow == null) {
                anon.setOwnerKey(userKey);
                readingBuddyPreferenceRepository.save(anon);
                continue;
            }

            // Last-write-wins; account (user) wins on equal updated_at.
            if (isAfter(anon.getUpdatedAt(), userRow.getUpdatedAt())) {
                if (ReadingBuddyPreferenceService.GLOBAL_BOOK_ID.equals(anon.getBookId())) {
                    userRow.setEnabled(anon.isEnabled());
                    userRow.setFrequency(anon.getFrequency());
                    userRow.setDefaultPersonaId(anon.getDefaultPersonaId());
                    userRow.setSuppressUntil(anon.getSuppressUntil());
                } else {
                    userRow.setPersonaId(anon.getPersonaId());
                }
                userRow.setUpdatedAt(anon.getUpdatedAt());
                readingBuddyPreferenceRepository.save(userRow);
            }
            readingBuddyPreferenceRepository.delete(anon);
        }
    }

    /**
     * Reassign or append anonymous messages. Per (book_id, persona_id): bulk rewrite if user
     * has zero history; else append by content_hash (and proactive position uniqueness).
     *
     * @return map of deleted anon message id → surviving user message id (null if dropped
     * without a survivor). Used to remap {@code last_message_id} on memory claim.
     */
    private Map<String, String> claimReadingBuddyMessages(String anonKey, String userKey) {
        Map<String, String> deletedAnonToSurvivor = new HashMap<>();
        List<ReadingBuddyMessageEntity> anonMessages =
                readingBuddyMessageRepository.findByOwnerKey(anonKey);
        if (anonMessages.isEmpty()) {
            return deletedAnonToSurvivor;
        }

        Map<String, List<ReadingBuddyMessageEntity>> anonByThread = new LinkedHashMap<>();
        for (ReadingBuddyMessageEntity msg : anonMessages) {
            String threadKey = threadKey(msg.getBookId(), msg.getPersonaId());
            anonByThread.computeIfAbsent(threadKey, ignored -> new ArrayList<>()).add(msg);
        }

        for (Map.Entry<String, List<ReadingBuddyMessageEntity>> entry : anonByThread.entrySet()) {
            List<ReadingBuddyMessageEntity> threadAnon = entry.getValue();
            ReadingBuddyMessageEntity sample = threadAnon.get(0);
            String bookId = sample.getBookId();
            String personaId = sample.getPersonaId();

            long userCount = readingBuddyMessageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                    userKey, bookId, personaId);
            if (userCount == 0) {
                for (ReadingBuddyMessageEntity anon : threadAnon) {
                    anon.setOwnerKey(userKey);
                    readingBuddyMessageRepository.save(anon);
                }
                continue;
            }

            List<ReadingBuddyMessageEntity> userMessages =
                    readingBuddyMessageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
                            userKey, bookId, personaId);
            Set<String> userHashes = new LinkedHashSet<>();
            Map<String, String> hashToUserMessageId = new HashMap<>();
            Map<String, ReadingBuddyMessageEntity> userProactiveByPosition = new HashMap<>();
            for (ReadingBuddyMessageEntity userMsg : userMessages) {
                if (userMsg.getContentHash() != null) {
                    userHashes.add(userMsg.getContentHash());
                    hashToUserMessageId.putIfAbsent(userMsg.getContentHash(), userMsg.getId());
                }
                if (userMsg.getProactivePositionKey() != null) {
                    userProactiveByPosition.put(userMsg.getProactivePositionKey(), userMsg);
                }
            }

            threadAnon.sort(Comparator.comparing(
                    ReadingBuddyMessageEntity::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ));

            for (ReadingBuddyMessageEntity anon : threadAnon) {
                if (anon.getContentHash() != null && userHashes.contains(anon.getContentHash())) {
                    recordDeletedAnonMessage(
                            deletedAnonToSurvivor,
                            anon.getId(),
                            hashToUserMessageId.get(anon.getContentHash())
                    );
                    readingBuddyMessageRepository.delete(anon);
                    continue;
                }

                String positionKey = anon.getProactivePositionKey();
                if (positionKey != null && userProactiveByPosition.containsKey(positionKey)) {
                    ReadingBuddyMessageEntity existing = userProactiveByPosition.get(positionKey);
                    // Keep earlier created_at; delete the other (always drop anon row).
                    if (isBefore(anon.getCreatedAt(), existing.getCreatedAt())) {
                        existing.setOwnerKey(userKey);
                        // Replace content with earlier anon message fields; keep existing PK.
                        existing.setRole(anon.getRole());
                        existing.setContent(anon.getContent());
                        existing.setKind(anon.getKind());
                        existing.setChapterIndex(anon.getChapterIndex());
                        existing.setParagraphIndex(anon.getParagraphIndex());
                        existing.setContentHash(anon.getContentHash());
                        existing.setCreatedAt(anon.getCreatedAt());
                        readingBuddyMessageRepository.save(existing);
                        if (anon.getContentHash() != null) {
                            userHashes.add(anon.getContentHash());
                            hashToUserMessageId.put(anon.getContentHash(), existing.getId());
                        }
                    }
                    recordDeletedAnonMessage(deletedAnonToSurvivor, anon.getId(), existing.getId());
                    readingBuddyMessageRepository.delete(anon);
                    continue;
                }

                anon.setOwnerKey(userKey);
                readingBuddyMessageRepository.save(anon);
                if (anon.getContentHash() != null) {
                    userHashes.add(anon.getContentHash());
                    hashToUserMessageId.putIfAbsent(anon.getContentHash(), anon.getId());
                }
                if (positionKey != null) {
                    userProactiveByPosition.put(positionKey, anon);
                }
            }
        }
        return deletedAnonToSurvivor;
    }

    /**
     * Memory rows: rewrite if only anon; if both, keep newer {@code updated_at} entire row.
     * Remaps {@code last_message_id} when the referenced anon message was deleted/merged.
     */
    private void claimReadingBuddyMemories(
            String anonKey,
            String userKey,
            Map<String, String> deletedAnonToSurvivor) {
        List<ReadingBuddyMemoryEntity> anonMemories =
                readingBuddyMemoryRepository.findByOwnerKey(anonKey);
        if (anonMemories.isEmpty()) {
            return;
        }

        for (ReadingBuddyMemoryEntity anon : anonMemories) {
            String remappedLastMessageId =
                    remapMessageId(anon.getLastMessageId(), deletedAnonToSurvivor);

            Optional<ReadingBuddyMemoryEntity> userOptional =
                    readingBuddyMemoryRepository.findByOwnerKeyAndBookIdAndPersonaId(
                            userKey, anon.getBookId(), anon.getPersonaId());
            if (userOptional.isEmpty()) {
                anon.setLastMessageId(remappedLastMessageId);
                anon.setOwnerKey(userKey);
                readingBuddyMemoryRepository.save(anon);
                continue;
            }

            ReadingBuddyMemoryEntity userRow = userOptional.get();
            // Keep newer updated_at; account wins on tie (do not prefer anon when equal).
            if (isAfter(anon.getUpdatedAt(), userRow.getUpdatedAt())) {
                userRow.setSummaryText(anon.getSummaryText());
                userRow.setSummaryVersion(anon.getSummaryVersion());
                userRow.setSummaryMaxChapterIndex(anon.getSummaryMaxChapterIndex());
                userRow.setSummaryMaxParagraphIndex(anon.getSummaryMaxParagraphIndex());
                userRow.setMessagesAtLastSummary(anon.getMessagesAtLastSummary());
                userRow.setLastMessageId(remappedLastMessageId);
                userRow.setUpdatedAt(anon.getUpdatedAt());
                readingBuddyMemoryRepository.save(userRow);
            }
            readingBuddyMemoryRepository.delete(anon);
        }
    }

    private static void recordDeletedAnonMessage(
            Map<String, String> deletedAnonToSurvivor,
            String anonMessageId,
            String survivingMessageId) {
        if (anonMessageId == null || anonMessageId.isBlank()) {
            return;
        }
        deletedAnonToSurvivor.put(anonMessageId, survivingMessageId);
    }

    /**
     * If {@code messageId} was a deleted anon message, return the surviving user message id
     * (possibly null). Otherwise leave unchanged.
     */
    private static String remapMessageId(String messageId, Map<String, String> deletedAnonToSurvivor) {
        if (messageId == null || deletedAnonToSurvivor == null || deletedAnonToSurvivor.isEmpty()) {
            return messageId;
        }
        if (!deletedAnonToSurvivor.containsKey(messageId)) {
            return messageId;
        }
        return deletedAnonToSurvivor.get(messageId);
    }

    private static String threadKey(String bookId, String personaId) {
        return bookId + "\0" + personaId;
    }

    private void claimParagraphAnnotations(String userId, String readerId) {
        List<ParagraphAnnotationEntity> sourceAnnotations =
                paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId);
        for (ParagraphAnnotationEntity source : sourceAnnotations) {
            Optional<ParagraphAnnotationEntity> targetOptional =
                    paragraphAnnotationRepository.findByUserIdAndBook_IdAndChapter_IdAndParagraphIndex(
                            userId,
                            source.getBook().getId(),
                            source.getChapter().getId(),
                            source.getParagraphIndex()
                    );
            if (targetOptional.isEmpty()) {
                source.setUserId(userId);
                paragraphAnnotationRepository.save(source);
                continue;
            }

            ParagraphAnnotationEntity target = targetOptional.get();
            if (isAfter(source.getUpdatedAt(), target.getUpdatedAt())) {
                target.setHighlighted(source.isHighlighted());
                target.setBookmarked(source.isBookmarked());
                target.setNoteText(source.getNoteText());
                paragraphAnnotationRepository.save(target);
            }
            paragraphAnnotationRepository.delete(source);
        }
    }

    private void claimQuizAttempts(String userId, String readerId) {
        List<QuizAttemptEntity> attempts = quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId);
        for (QuizAttemptEntity attempt : attempts) {
            attempt.setUserId(userId);
            quizAttemptRepository.save(attempt);
        }
    }

    private void claimQuizTrophies(String userId, String readerId) {
        List<QuizTrophyEntity> trophies = quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId);
        for (QuizTrophyEntity trophy : trophies) {
            Optional<QuizTrophyEntity> existing =
                    quizTrophyRepository.findByBookIdAndUserIdAndCode(
                            trophy.getBook().getId(),
                            userId,
                            trophy.getCode()
                    );
            if (existing.isEmpty()) {
                trophy.setUserId(userId);
                quizTrophyRepository.save(trophy);
                continue;
            }

            QuizTrophyEntity target = existing.get();
            if (isBefore(trophy.getUnlockedAt(), target.getUnlockedAt())) {
                target.setUnlockedAt(trophy.getUnlockedAt());
                quizTrophyRepository.save(target);
            }
            quizTrophyRepository.delete(trophy);
        }
    }

    private AccountStateSnapshot merge(AccountStateSnapshot existing, AccountStateSnapshot incoming) {
        AccountStateSnapshot normalizedExisting = normalize(existing);

        List<String> mergedFavorites = mergeFavorites(
                normalizedExisting.favoriteBookIds(),
                incoming.favoriteBookIds()
        );
        Map<String, AccountStateSnapshot.BookActivity> mergedBookActivity = mergeBookActivity(
                normalizedExisting.bookActivity(),
                incoming.bookActivity()
        );
        AccountStateSnapshot.ReaderPreferences mergedPreferences = mergeReaderPreferences(
                normalizedExisting.readerPreferences(),
                incoming.readerPreferences()
        );
        Map<String, Boolean> mergedRecapOptOut = mergeRecapOptOut(
                normalizedExisting.recapOptOut(),
                incoming.recapOptOut()
        );

        return new AccountStateSnapshot(
                mergedFavorites,
                mergedBookActivity,
                mergedPreferences,
                mergedRecapOptOut
        );
    }

    private AccountStateSnapshot normalize(AccountStateSnapshot snapshot) {
        if (snapshot == null) {
            return AccountStateSnapshot.empty();
        }

        List<String> favoriteBookIds = sanitizeFavoriteBookIds(snapshot.favoriteBookIds());
        Map<String, AccountStateSnapshot.BookActivity> bookActivity = sanitizeBookActivity(snapshot.bookActivity());
        AccountStateSnapshot.ReaderPreferences readerPreferences = sanitizeReaderPreferences(snapshot.readerPreferences());
        Map<String, Boolean> recapOptOut = sanitizeRecapOptOut(snapshot.recapOptOut());

        return new AccountStateSnapshot(favoriteBookIds, bookActivity, readerPreferences, recapOptOut);
    }

    private List<String> sanitizeFavoriteBookIds(List<String> favoriteBookIds) {
        if (favoriteBookIds == null || favoriteBookIds.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String bookId : favoriteBookIds) {
            if (bookId == null) {
                continue;
            }
            String trimmed = bookId.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            seen.add(trimmed);
        }
        return List.copyOf(seen);
    }

    private Map<String, AccountStateSnapshot.BookActivity> sanitizeBookActivity(
            Map<String, AccountStateSnapshot.BookActivity> bookActivity) {
        if (bookActivity == null || bookActivity.isEmpty()) {
            return Map.of();
        }
        Map<String, AccountStateSnapshot.BookActivity> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, AccountStateSnapshot.BookActivity> entry : bookActivity.entrySet()) {
            String bookId = entry.getKey();
            if (bookId == null || bookId.isBlank()) {
                continue;
            }
            AccountStateSnapshot.BookActivity activity = entry.getValue();
            if (activity == null) {
                continue;
            }
            normalized.put(bookId.trim(), normalizeBookActivity(activity));
        }
        return Map.copyOf(normalized);
    }

    private AccountStateSnapshot.BookActivity normalizeBookActivity(AccountStateSnapshot.BookActivity activity) {
        double progressRatio = clamp(toDouble(activity.progressRatio(), 0.0), 0.0, 1.0);
        double maxProgressRatio = clamp(
                Math.max(progressRatio, toDouble(activity.maxProgressRatio(), progressRatio)),
                0.0,
                1.0
        );
        return new AccountStateSnapshot.BookActivity(
                positiveOrNull(activity.chapterCount()),
                nonNegativeOrNull(activity.lastChapterIndex()),
                nonNegativeOrNull(activity.lastPage()),
                positiveOrNull(activity.totalPages()),
                progressRatio,
                maxProgressRatio,
                Boolean.TRUE.equals(activity.completed()) || maxProgressRatio >= 0.999,
                nonNegativeOrZero(activity.openCount()),
                trimToNull(activity.lastOpenedAt()),
                trimToNull(activity.lastReadAt()),
                trimToNull(activity.completedAt()),
                sanitizeCompletedChapterIndexes(activity.completedChapterIndexes(), activity.chapterCount())
        );
    }

    private AccountStateSnapshot.ReaderPreferences sanitizeReaderPreferences(
            AccountStateSnapshot.ReaderPreferences preferences) {
        if (preferences == null) {
            return null;
        }
        return new AccountStateSnapshot.ReaderPreferences(
                clampOrNull(preferences.fontSize(), 1.0, 1.5),
                clampOrNull(preferences.lineHeight(), 1.4, 2.1),
                clampOrNull(preferences.columnGap(), 2.0, 6.0),
                normalizeTheme(preferences.theme()),
                booleanOrDefault(preferences.recapTabEnabled(), true),
                booleanOrDefault(preferences.chatTabEnabled(), true),
                booleanOrDefault(preferences.quizTabEnabled(), true),
                trimToNull(preferences.updatedAt())
        );
    }

    private Map<String, Boolean> sanitizeRecapOptOut(Map<String, Boolean> recapOptOut) {
        if (recapOptOut == null || recapOptOut.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : recapOptOut.entrySet()) {
            String bookId = entry.getKey();
            if (bookId == null || bookId.isBlank()) {
                continue;
            }
            normalized.put(bookId.trim(), Boolean.TRUE.equals(entry.getValue()));
        }
        return Map.copyOf(normalized);
    }

    private List<String> mergeFavorites(List<String> existing, List<String> incoming) {
        List<String> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String bookId : incoming) {
            if (seen.add(bookId)) {
                merged.add(bookId);
            }
        }
        for (String bookId : existing) {
            if (seen.add(bookId)) {
                merged.add(bookId);
            }
        }
        return List.copyOf(merged);
    }

    private Map<String, AccountStateSnapshot.BookActivity> mergeBookActivity(
            Map<String, AccountStateSnapshot.BookActivity> existing,
            Map<String, AccountStateSnapshot.BookActivity> incoming) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(existing.keySet());
        keys.addAll(incoming.keySet());

        Map<String, AccountStateSnapshot.BookActivity> merged = new LinkedHashMap<>();
        for (String key : keys) {
            AccountStateSnapshot.BookActivity a = existing.get(key);
            AccountStateSnapshot.BookActivity b = incoming.get(key);
            if (a == null) {
                merged.put(key, b);
                continue;
            }
            if (b == null) {
                merged.put(key, a);
                continue;
            }
            merged.put(key, mergeBookActivityValue(a, b));
        }
        return Map.copyOf(merged);
    }

    private AccountStateSnapshot.BookActivity mergeBookActivityValue(
            AccountStateSnapshot.BookActivity existing,
            AccountStateSnapshot.BookActivity incoming) {
        AccountStateSnapshot.BookActivity primary = pickMoreRecentBookActivity(existing, incoming);

        double mergedProgressRatio = Math.max(
                toDouble(existing.progressRatio(), 0.0),
                toDouble(incoming.progressRatio(), 0.0)
        );
        double mergedMaxProgressRatio = Math.max(
                toDouble(existing.maxProgressRatio(), mergedProgressRatio),
                toDouble(incoming.maxProgressRatio(), mergedProgressRatio)
        );

        return new AccountStateSnapshot.BookActivity(
                firstNonNullPositive(primary.chapterCount(), existing.chapterCount(), incoming.chapterCount()),
                firstNonNullNonNegative(primary.lastChapterIndex(), existing.lastChapterIndex(), incoming.lastChapterIndex()),
                firstNonNullNonNegative(primary.lastPage(), existing.lastPage(), incoming.lastPage()),
                firstNonNullPositive(primary.totalPages(), existing.totalPages(), incoming.totalPages()),
                clamp(mergedProgressRatio, 0.0, 1.0),
                clamp(mergedMaxProgressRatio, 0.0, 1.0),
                Boolean.TRUE.equals(existing.completed()) || Boolean.TRUE.equals(incoming.completed()),
                Math.max(nonNegativeOrZero(existing.openCount()), nonNegativeOrZero(incoming.openCount())),
                latestTimestamp(existing.lastOpenedAt(), incoming.lastOpenedAt()),
                latestTimestamp(existing.lastReadAt(), incoming.lastReadAt()),
                latestTimestamp(existing.completedAt(), incoming.completedAt()),
                sanitizeCompletedChapterIndexes(
                        unionCompletedChapters(existing.completedChapterIndexes(), incoming.completedChapterIndexes()),
                        firstNonNullPositive(primary.chapterCount(), existing.chapterCount(), incoming.chapterCount()))
        );
    }

    private AccountStateSnapshot.BookActivity pickMoreRecentBookActivity(
            AccountStateSnapshot.BookActivity a,
            AccountStateSnapshot.BookActivity b) {
        return List.of(a, b).stream()
                .filter(Objects::nonNull)
                .max(Comparator
                        .comparing((AccountStateSnapshot.BookActivity item) -> toEpochMilli(item.lastReadAt()))
                        .thenComparing(item -> toDouble(item.maxProgressRatio(), 0.0)))
                .orElse(a);
    }

    private AccountStateSnapshot.ReaderPreferences mergeReaderPreferences(
            AccountStateSnapshot.ReaderPreferences existing,
            AccountStateSnapshot.ReaderPreferences incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }

        long existingTs = toEpochMilli(existing.updatedAt());
        long incomingTs = toEpochMilli(incoming.updatedAt());
        if (incomingTs >= existingTs) {
            return incoming;
        }
        return existing;
    }

    private Map<String, Boolean> mergeRecapOptOut(
            Map<String, Boolean> existing,
            Map<String, Boolean> incoming) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(existing.keySet());
        keys.addAll(incoming.keySet());

        Map<String, Boolean> merged = new LinkedHashMap<>();
        for (String key : keys) {
            boolean value = Boolean.TRUE.equals(existing.get(key)) || Boolean.TRUE.equals(incoming.get(key));
            merged.put(key, value);
        }
        return Map.copyOf(merged);
    }

    private String normalizeTheme(String theme) {
        if (theme == null || theme.isBlank()) {
            return null;
        }
        String normalized = theme.trim().toLowerCase();
        if (!normalized.equals("warm") && !normalized.equals("paper")) {
            return null;
        }
        return normalized;
    }

    private Integer positiveOrNull(Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private Integer nonNegativeOrNull(Integer value) {
        if (value == null || value < 0) {
            return null;
        }
        return value;
    }

    private Integer nonNegativeOrZero(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    private Integer firstNonNullPositive(Integer preferred, Integer a, Integer b) {
        Integer normalizedPreferred = positiveOrNull(preferred);
        if (normalizedPreferred != null) {
            return normalizedPreferred;
        }
        Integer normalizedA = positiveOrNull(a);
        if (normalizedA != null) {
            return normalizedA;
        }
        return positiveOrNull(b);
    }

    private Integer firstNonNullNonNegative(Integer preferred, Integer a, Integer b) {
        Integer normalizedPreferred = nonNegativeOrNull(preferred);
        if (normalizedPreferred != null) {
            return normalizedPreferred;
        }
        Integer normalizedA = nonNegativeOrNull(a);
        if (normalizedA != null) {
            return normalizedA;
        }
        return nonNegativeOrNull(b);
    }

    private double toDouble(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private Double clampOrNull(Double value, double min, double max) {
        if (value == null) {
            return null;
        }
        return clamp(value, min, max);
    }

    private Boolean booleanOrDefault(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isAfter(java.time.LocalDateTime a, java.time.LocalDateTime b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return a.isAfter(b);
    }

    private boolean isBefore(java.time.LocalDateTime a, java.time.LocalDateTime b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return a.isBefore(b);
    }

    private List<Integer> unionCompletedChapters(List<Integer> left, List<Integer> right) {
        List<Integer> combined = new ArrayList<>();
        if (left != null) {
            combined.addAll(left);
        }
        if (right != null) {
            combined.addAll(right);
        }
        return sanitizeCompletedChapterIndexes(combined, null);
    }

    private List<Integer> sanitizeCompletedChapterIndexes(List<Integer> indexes, Integer chapterCount) {
        if (indexes == null || indexes.isEmpty()) {
            return List.of();
        }
        int maxIndex = MAX_CHAPTER_INDEX;
        Integer normalizedCount = positiveOrNull(chapterCount);
        if (normalizedCount != null) {
            maxIndex = Math.min(MAX_CHAPTER_INDEX, normalizedCount - 1);
        }
        java.util.TreeSet<Integer> unique = new java.util.TreeSet<>();
        for (Integer index : indexes) {
            if (index == null || index < 0 || index > maxIndex) {
                continue;
            }
            unique.add(index);
        }
        if (unique.size() <= MAX_COMPLETED_CHAPTER_INDEXES) {
            return List.copyOf(unique);
        }
        return unique.stream().limit(MAX_COMPLETED_CHAPTER_INDEXES).toList();
    }

    private String latestTimestamp(String a, String b) {
        if (toEpochMilli(a) >= toEpochMilli(b)) {
            return trimToNull(a);
        }
        return trimToNull(b);
    }

    private long toEpochMilli(String timestamp) {
        String normalized = trimToNull(timestamp);
        if (normalized == null) {
            return 0L;
        }
        try {
            return Instant.parse(normalized).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AccountStateSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return AccountStateSnapshot.empty();
        }
        try {
            AccountStateSnapshot snapshot = objectMapper.readValue(json, AccountStateSnapshot.class);
            return normalize(snapshot);
        } catch (Exception ignored) {
            return AccountStateSnapshot.empty();
        }
    }

    private String toJson(AccountStateSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize account state", e);
        }
    }

    public record ClaimSyncResult(boolean claimApplied, AccountStateSnapshot state) {
    }
}
