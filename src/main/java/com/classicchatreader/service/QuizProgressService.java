package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.QuizTrophyEntity;
import com.classicchatreader.model.QuizProgress;
import com.classicchatreader.model.QuizTrophy;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class QuizProgressService {

    private static final TrophyDefinition FIRST_ATTEMPT = new TrophyDefinition(
            "quiz_first_attempt",
            "First Checkpoint",
            "Complete your first chapter quiz."
    );

    private static final TrophyDefinition FIRST_PERFECT = new TrophyDefinition(
            "quiz_first_perfect",
            "Perfect Recall",
            "Score 100% on any chapter quiz."
    );

    private static final TrophyDefinition PERFECT_STREAK_THREE = new TrophyDefinition(
            "quiz_perfect_streak_3",
            "Hot Streak",
            "Get 3 perfect quiz scores in a row."
    );

    private static final TrophyDefinition HARD_MODE_PERFECT = new TrophyDefinition(
            "quiz_hard_mode_perfect",
            "High Difficulty Ace",
            "Score 100% on a high-difficulty quiz."
    );

    private final QuizAttemptRepository quizAttemptRepository;
    private final QuizTrophyRepository quizTrophyRepository;

    public QuizProgressService(
            QuizAttemptRepository quizAttemptRepository,
            QuizTrophyRepository quizTrophyRepository) {
        this.quizAttemptRepository = quizAttemptRepository;
        this.quizTrophyRepository = quizTrophyRepository;
    }

    @Transactional
    public ProgressUpdate recordAttemptAndEvaluate(
            ChapterEntity chapter,
            int scorePercent,
            int correctAnswers,
            int totalQuestions,
            int difficultyLevel) {
        return recordAttemptAndEvaluate(chapter, null, null, scorePercent, correctAnswers, totalQuestions, difficultyLevel);
    }

    @Transactional
    public ProgressUpdate recordAttemptAndEvaluate(
            ChapterEntity chapter,
            String userId,
            int scorePercent,
            int correctAnswers,
            int totalQuestions,
            int difficultyLevel) {
        return recordAttemptAndEvaluate(chapter, null, userId, scorePercent, correctAnswers, totalQuestions, difficultyLevel);
    }

    @Transactional
    public ProgressUpdate recordAttemptAndEvaluate(
            ChapterEntity chapter,
            String readerId,
            String userId,
            int scorePercent,
            int correctAnswers,
            int totalQuestions,
            int difficultyLevel) {
        String scopedReaderId = normalizeReaderId(readerId, userId);
        QuizAttemptEntity attempt = new QuizAttemptEntity();
        attempt.setChapter(chapter);
        attempt.setReaderId(scopedReaderId);
        attempt.setUserId(userId);
        attempt.setScorePercent(scorePercent);
        attempt.setCorrectAnswers(correctAnswers);
        attempt.setTotalQuestions(totalQuestions);
        attempt.setPerfect(scorePercent == 100);
        attempt.setDifficultyLevel(difficultyLevel);
        quizAttemptRepository.save(attempt);

        String bookId = chapter.getBook().getId();
        long totalAttempts = resolveTotalAttempts(bookId, scopedReaderId, userId);
        long perfectAttempts = resolvePerfectAttempts(bookId, scopedReaderId, userId);
        int currentPerfectStreak = calculateCurrentPerfectStreak(bookId, scopedReaderId, userId);

        List<QuizTrophy> unlocked = new ArrayList<>();
        unlockIfEligible(chapter.getBook(), scopedReaderId, userId, FIRST_ATTEMPT, totalAttempts >= 1, unlocked);
        unlockIfEligible(chapter.getBook(), scopedReaderId, userId, FIRST_PERFECT, scorePercent == 100, unlocked);
        unlockIfEligible(chapter.getBook(), scopedReaderId, userId, PERFECT_STREAK_THREE, currentPerfectStreak >= 3, unlocked);
        unlockIfEligible(chapter.getBook(), scopedReaderId, userId, HARD_MODE_PERFECT, scorePercent == 100 && difficultyLevel >= 2, unlocked);

        return new ProgressUpdate(
                unlocked,
                new QuizProgress(totalAttempts, perfectAttempts, currentPerfectStreak)
        );
    }

    @Transactional(readOnly = true)
    public List<QuizTrophy> getBookTrophies(String bookId) {
        return getBookTrophies(bookId, null, null);
    }

    @Transactional(readOnly = true)
    public List<QuizTrophy> getBookTrophies(String bookId, String userId) {
        return getBookTrophies(bookId, null, userId);
    }

    @Transactional(readOnly = true)
    public List<QuizTrophy> getBookTrophies(String bookId, String readerId, String userId) {
        String scopedReaderId = normalizeReaderId(readerId, userId);
        List<QuizTrophyEntity> trophies = resolveTrophies(bookId, scopedReaderId, userId);
        return trophies.stream()
                .map(this::toModel)
                .toList();
    }

    private int calculateCurrentPerfectStreak(String bookId, String readerId, String userId) {
        List<QuizAttemptEntity> recentAttempts;
        if (userId != null) {
            recentAttempts = quizAttemptRepository.findByChapterBookIdAndUserIdOrderByCreatedAtDesc(bookId, userId);
        } else if (readerId != null) {
            recentAttempts = quizAttemptRepository.findByChapterBookIdAndReaderIdOrderByCreatedAtDesc(bookId, readerId);
        } else {
            recentAttempts = quizAttemptRepository.findByChapterBookIdOrderByCreatedAtDesc(bookId);
        }
        int streak = 0;
        for (QuizAttemptEntity attempt : recentAttempts) {
            if (attempt.isPerfect()) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    private void unlockIfEligible(
            BookEntity book,
            String readerId,
            String userId,
            TrophyDefinition trophy,
            boolean eligible,
            List<QuizTrophy> unlocked) {
        if (!eligible) {
            return;
        }
        String bookId = book.getId();
        Optional<QuizTrophyEntity> existing;
        if (userId != null) {
            existing = quizTrophyRepository.findByBookIdAndUserIdAndCode(bookId, userId, trophy.code());
        } else if (readerId != null) {
            existing = quizTrophyRepository.findByBookIdAndReaderIdAndCode(bookId, readerId, trophy.code());
        } else {
            existing = quizTrophyRepository.findByBookIdAndCode(bookId, trophy.code());
        }
        if (existing.isPresent()) {
            return;
        }

        QuizTrophyEntity entity = new QuizTrophyEntity();
        entity.setBook(book);
        entity.setReaderId(readerId);
        entity.setUserId(userId);
        entity.setCode(trophy.code());
        entity.setTitle(trophy.title());
        entity.setDescription(trophy.description());

        try {
            QuizTrophyEntity saved = quizTrophyRepository.save(entity);
            unlocked.add(toModel(saved));
        } catch (DataIntegrityViolationException ignored) {
            // Duplicate unlock race; safe to ignore.
        }
    }

    private long resolveTotalAttempts(String bookId, String readerId, String userId) {
        if (userId != null) {
            return quizAttemptRepository.countByChapterBookIdAndUserId(bookId, userId);
        }
        if (readerId != null) {
            return quizAttemptRepository.countByChapterBookIdAndReaderId(bookId, readerId);
        }
        return quizAttemptRepository.countByChapterBookId(bookId);
    }

    private long resolvePerfectAttempts(String bookId, String readerId, String userId) {
        if (userId != null) {
            return quizAttemptRepository.countByChapterBookIdAndUserIdAndPerfectTrue(bookId, userId);
        }
        if (readerId != null) {
            return quizAttemptRepository.countByChapterBookIdAndReaderIdAndPerfectTrue(bookId, readerId);
        }
        return quizAttemptRepository.countByChapterBookIdAndPerfectTrue(bookId);
    }

    private List<QuizTrophyEntity> resolveTrophies(String bookId, String readerId, String userId) {
        if (userId != null) {
            return quizTrophyRepository.findByBookIdAndUserIdOrderByUnlockedAtDesc(bookId, userId);
        }
        if (readerId != null) {
            return quizTrophyRepository.findByBookIdAndReaderIdOrderByUnlockedAtDesc(bookId, readerId);
        }
        return quizTrophyRepository.findByBookIdOrderByUnlockedAtDesc(bookId);
    }

    private String normalizeReaderId(String readerId, String userId) {
        if (userId != null) {
            return null;
        }
        if (readerId == null) {
            return null;
        }
        String normalized = readerId.trim();
        if (normalized.isEmpty() || normalized.startsWith("user:")) {
            return null;
        }
        return normalized;
    }

    private QuizTrophy toModel(QuizTrophyEntity entity) {
        return new QuizTrophy(
                entity.getCode(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getUnlockedAt()
        );
    }

    public record ProgressUpdate(
            List<QuizTrophy> newlyUnlocked,
            QuizProgress progress
    ) {
    }

    private record TrophyDefinition(
            String code,
            String title,
            String description
    ) {
    }
}
