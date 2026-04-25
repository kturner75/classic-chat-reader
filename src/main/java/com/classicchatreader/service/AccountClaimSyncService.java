package com.classicchatreader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.entity.ParagraphAnnotationEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.QuizTrophyEntity;
import com.classicchatreader.entity.UserReaderClaimEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.model.AccountStateSnapshot;
import com.classicchatreader.repository.ParagraphAnnotationRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
import com.classicchatreader.repository.UserReaderClaimRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class AccountClaimSyncService {

    private final ParagraphAnnotationRepository paragraphAnnotationRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final QuizTrophyRepository quizTrophyRepository;
    private final UserReaderStateRepository userReaderStateRepository;
    private final UserReaderClaimRepository userReaderClaimRepository;
    private final ObjectMapper objectMapper;

    public AccountClaimSyncService(
            ParagraphAnnotationRepository paragraphAnnotationRepository,
            QuizAttemptRepository quizAttemptRepository,
            QuizTrophyRepository quizTrophyRepository,
            UserReaderStateRepository userReaderStateRepository,
            UserReaderClaimRepository userReaderClaimRepository,
            ObjectMapper objectMapper) {
        this.paragraphAnnotationRepository = paragraphAnnotationRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.quizTrophyRepository = quizTrophyRepository;
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
                trimToNull(activity.completedAt())
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
                latestTimestamp(existing.completedAt(), incoming.completedAt())
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
