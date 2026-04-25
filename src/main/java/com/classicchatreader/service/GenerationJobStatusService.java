package com.classicchatreader.service;

import com.classicchatreader.entity.ChapterAnalysisStatus;
import com.classicchatreader.entity.ChapterRecapStatus;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.model.GenerationJobStatusResponse;
import com.classicchatreader.model.GenerationPipelineStatus;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRecapRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.IllustrationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class GenerationJobStatusService {

    private final IllustrationRepository illustrationRepository;
    private final CharacterRepository characterRepository;
    private final ChapterAnalysisRepository chapterAnalysisRepository;
    private final ChapterRecapRepository chapterRecapRepository;

    public GenerationJobStatusService(
            IllustrationRepository illustrationRepository,
            CharacterRepository characterRepository,
            ChapterAnalysisRepository chapterAnalysisRepository,
            ChapterRecapRepository chapterRecapRepository) {
        this.illustrationRepository = illustrationRepository;
        this.characterRepository = characterRepository;
        this.chapterAnalysisRepository = chapterAnalysisRepository;
        this.chapterRecapRepository = chapterRecapRepository;
    }

    public GenerationJobStatusResponse getGlobalStatus() {
        LocalDateTime now = LocalDateTime.now();
        GenerationPipelineStatus illustrations = illustrationStatus(now);
        GenerationPipelineStatus portraits = portraitStatus(now);
        GenerationPipelineStatus analyses = analysisStatus(now);
        GenerationPipelineStatus recaps = recapStatus(now);

        return new GenerationJobStatusResponse(
                "global",
                null,
                now,
                illustrations,
                portraits,
                analyses,
                recaps,
                sum(illustrations, portraits, analyses, recaps)
        );
    }

    public GenerationJobStatusResponse getBookStatus(String bookId) {
        LocalDateTime now = LocalDateTime.now();
        GenerationPipelineStatus illustrations = illustrationStatusForBook(bookId, now);
        GenerationPipelineStatus portraits = portraitStatusForBook(bookId, now);
        GenerationPipelineStatus analyses = analysisStatusForBook(bookId, now);
        GenerationPipelineStatus recaps = recapStatusForBook(bookId, now);

        return new GenerationJobStatusResponse(
                "book",
                bookId,
                now,
                illustrations,
                portraits,
                analyses,
                recaps,
                sum(illustrations, portraits, analyses, recaps)
        );
    }

    private GenerationPipelineStatus illustrationStatus(LocalDateTime now) {
        long pendingTotal = illustrationRepository.countByStatus(IllustrationStatus.PENDING);
        long retryScheduled = illustrationRepository.countScheduledRetries(IllustrationStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                illustrationRepository.countByStatus(IllustrationStatus.GENERATING),
                illustrationRepository.countByStatus(IllustrationStatus.COMPLETED),
                illustrationRepository.countByStatus(IllustrationStatus.FAILED)
        );
    }

    private GenerationPipelineStatus illustrationStatusForBook(String bookId, LocalDateTime now) {
        long pendingTotal = illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.PENDING);
        long retryScheduled = illustrationRepository.countScheduledRetriesForBook(bookId, IllustrationStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.GENERATING),
                illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.COMPLETED),
                illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.FAILED)
        );
    }

    private GenerationPipelineStatus portraitStatus(LocalDateTime now) {
        long pendingTotal = characterRepository.countByStatus(CharacterStatus.PENDING);
        long retryScheduled = characterRepository.countScheduledRetries(CharacterStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                characterRepository.countByStatus(CharacterStatus.GENERATING),
                characterRepository.countByStatus(CharacterStatus.COMPLETED),
                characterRepository.countByStatus(CharacterStatus.FAILED)
        );
    }

    private GenerationPipelineStatus portraitStatusForBook(String bookId, LocalDateTime now) {
        long pendingTotal = characterRepository.countByBookAndStatus(bookId, CharacterStatus.PENDING);
        long retryScheduled = characterRepository.countScheduledRetriesForBook(bookId, CharacterStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                characterRepository.countByBookAndStatus(bookId, CharacterStatus.GENERATING),
                characterRepository.countByBookAndStatus(bookId, CharacterStatus.COMPLETED),
                characterRepository.countByBookAndStatus(bookId, CharacterStatus.FAILED)
        );
    }

    private GenerationPipelineStatus analysisStatus(LocalDateTime now) {
        long pendingTotal = chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.PENDING)
                + chapterAnalysisRepository.countByStatusIsNull();
        long retryScheduled = chapterAnalysisRepository.countScheduledRetries(ChapterAnalysisStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.GENERATING),
                chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.COMPLETED),
                chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.FAILED)
        );
    }

    private GenerationPipelineStatus analysisStatusForBook(String bookId, LocalDateTime now) {
        long pendingTotal = chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.PENDING)
                + chapterAnalysisRepository.countByChapterBookIdAndStatusIsNull(bookId);
        long retryScheduled = chapterAnalysisRepository.countScheduledRetriesForBook(bookId, ChapterAnalysisStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.GENERATING),
                chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.COMPLETED),
                chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.FAILED)
        );
    }

    private GenerationPipelineStatus recapStatus(LocalDateTime now) {
        long pendingTotal = chapterRecapRepository.countByStatus(ChapterRecapStatus.PENDING)
                + chapterRecapRepository.countByStatusIsNull();
        long retryScheduled = chapterRecapRepository.countScheduledRetries(ChapterRecapStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                chapterRecapRepository.countByStatus(ChapterRecapStatus.GENERATING),
                chapterRecapRepository.countByStatus(ChapterRecapStatus.COMPLETED),
                chapterRecapRepository.countByStatus(ChapterRecapStatus.FAILED)
        );
    }

    private GenerationPipelineStatus recapStatusForBook(String bookId, LocalDateTime now) {
        long pendingTotal = chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.PENDING)
                + chapterRecapRepository.countByChapterBookIdAndStatusIsNull(bookId);
        long retryScheduled = chapterRecapRepository.countScheduledRetriesForBook(bookId, ChapterRecapStatus.PENDING, now);
        return GenerationPipelineStatus.of(
                pendingTotal - retryScheduled,
                retryScheduled,
                chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.GENERATING),
                chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.COMPLETED),
                chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.FAILED)
        );
    }

    private GenerationPipelineStatus sum(GenerationPipelineStatus... statuses) {
        long pending = 0L;
        long retryScheduled = 0L;
        long generating = 0L;
        long completed = 0L;
        long failed = 0L;
        for (GenerationPipelineStatus status : statuses) {
            pending += status.pending();
            retryScheduled += status.retryScheduled();
            generating += status.generating();
            completed += status.completed();
            failed += status.failed();
        }
        return GenerationPipelineStatus.of(pending, retryScheduled, generating, completed, failed);
    }
}
