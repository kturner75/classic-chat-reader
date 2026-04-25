package com.classicchatreader.repository;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterAnalysisEntity;
import com.classicchatreader.entity.ChapterAnalysisStatus;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ChapterRecapEntity;
import com.classicchatreader.entity.ChapterRecapStatus;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
class GenerationLeaseClaimRepositoryTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChapterRepository chapterRepository;

    @Autowired
    private IllustrationRepository illustrationRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private ChapterAnalysisRepository chapterAnalysisRepository;

    @Autowired
    private ChapterRecapRepository chapterRecapRepository;

    @Test
    void illustrationClaim_preventsDoubleClaimAndAllowsStaleReclaim() {
        ChapterEntity chapter = persistChapter("book-illustration");
        illustrationRepository.save(new IllustrationEntity(chapter));

        LocalDateTime now = LocalDateTime.now();
        int claimed = illustrationRepository.claimGenerationLease(
                chapter.getId(),
                now,
                now.plusMinutes(10),
                "illustration-worker-a",
                IllustrationStatus.PENDING,
                IllustrationStatus.GENERATING
        );
        assertEquals(1, claimed);

        IllustrationEntity claimedEntity = illustrationRepository.findByChapterId(chapter.getId()).orElseThrow();
        assertEquals(IllustrationStatus.GENERATING, claimedEntity.getStatus());
        assertEquals("illustration-worker-a", claimedEntity.getLeaseOwner());
        assertNotNull(claimedEntity.getLeaseExpiresAt());

        int secondClaim = illustrationRepository.claimGenerationLease(
                chapter.getId(),
                now.plusMinutes(1),
                now.plusMinutes(11),
                "illustration-worker-b",
                IllustrationStatus.PENDING,
                IllustrationStatus.GENERATING
        );
        assertEquals(0, secondClaim);

        claimedEntity.setLeaseExpiresAt(now.minusMinutes(1));
        illustrationRepository.save(claimedEntity);

        int staleReclaim = illustrationRepository.claimGenerationLease(
                chapter.getId(),
                now.plusMinutes(2),
                now.plusMinutes(12),
                "illustration-worker-b",
                IllustrationStatus.PENDING,
                IllustrationStatus.GENERATING
        );
        assertEquals(1, staleReclaim);

        IllustrationEntity reclaimedEntity = illustrationRepository.findByChapterId(chapter.getId()).orElseThrow();
        assertEquals("illustration-worker-b", reclaimedEntity.getLeaseOwner());
    }

    @Test
    void illustrationClaim_blocksUntilRetryWindowExpires() {
        ChapterEntity chapter = persistChapter("book-illustration-retry");
        IllustrationEntity entity = new IllustrationEntity(chapter);
        entity.setNextRetryAt(LocalDateTime.now().plusMinutes(3));
        illustrationRepository.save(entity);

        LocalDateTime now = LocalDateTime.now();
        int earlyClaim = illustrationRepository.claimGenerationLease(
                chapter.getId(),
                now,
                now.plusMinutes(10),
                "illustration-worker-a",
                IllustrationStatus.PENDING,
                IllustrationStatus.GENERATING
        );
        assertEquals(0, earlyClaim);

        int dueClaim = illustrationRepository.claimGenerationLease(
                chapter.getId(),
                now.plusMinutes(4),
                now.plusMinutes(14),
                "illustration-worker-a",
                IllustrationStatus.PENDING,
                IllustrationStatus.GENERATING
        );
        assertEquals(1, dueClaim);
    }

    @Test
    void portraitClaim_preventsDoubleClaimAndAllowsStaleReclaim() {
        ChapterEntity chapter = persistChapter("book-character");
        CharacterEntity character = new CharacterEntity(chapter.getBook(), "Alice", "Companion", chapter, 0);
        character = characterRepository.save(character);

        LocalDateTime now = LocalDateTime.now();
        int claimed = characterRepository.claimPortraitLease(
                character.getId(),
                now,
                now.plusMinutes(8),
                "character-worker-a",
                CharacterStatus.PENDING,
                CharacterStatus.GENERATING
        );
        assertEquals(1, claimed);

        CharacterEntity claimedEntity = characterRepository.findById(character.getId()).orElseThrow();
        assertEquals(CharacterStatus.GENERATING, claimedEntity.getStatus());
        assertEquals("character-worker-a", claimedEntity.getLeaseOwner());
        assertNotNull(claimedEntity.getLeaseExpiresAt());

        int secondClaim = characterRepository.claimPortraitLease(
                character.getId(),
                now.plusMinutes(1),
                now.plusMinutes(9),
                "character-worker-b",
                CharacterStatus.PENDING,
                CharacterStatus.GENERATING
        );
        assertEquals(0, secondClaim);

        claimedEntity.setLeaseExpiresAt(now.minusMinutes(1));
        characterRepository.save(claimedEntity);

        int staleReclaim = characterRepository.claimPortraitLease(
                character.getId(),
                now.plusMinutes(2),
                now.plusMinutes(10),
                "character-worker-b",
                CharacterStatus.PENDING,
                CharacterStatus.GENERATING
        );
        assertEquals(1, staleReclaim);

        CharacterEntity reclaimedEntity = characterRepository.findById(character.getId()).orElseThrow();
        assertEquals("character-worker-b", reclaimedEntity.getLeaseOwner());
    }

    @Test
    void portraitClaim_blocksUntilRetryWindowExpires() {
        ChapterEntity chapter = persistChapter("book-character-retry");
        CharacterEntity character = new CharacterEntity(chapter.getBook(), "Alice", "Companion", chapter, 0);
        character.setNextRetryAt(LocalDateTime.now().plusMinutes(2));
        character = characterRepository.save(character);

        LocalDateTime now = LocalDateTime.now();
        int earlyClaim = characterRepository.claimPortraitLease(
                character.getId(),
                now,
                now.plusMinutes(8),
                "character-worker-a",
                CharacterStatus.PENDING,
                CharacterStatus.GENERATING
        );
        assertEquals(0, earlyClaim);

        int dueClaim = characterRepository.claimPortraitLease(
                character.getId(),
                now.plusMinutes(3),
                now.plusMinutes(9),
                "character-worker-a",
                CharacterStatus.PENDING,
                CharacterStatus.GENERATING
        );
        assertEquals(1, dueClaim);
    }

    @Test
    void analysisClaim_preventsDoubleClaimAndAllowsStaleReclaim() {
        ChapterEntity chapter = persistChapter("book-analysis");
        ChapterAnalysisEntity analysis = chapterAnalysisRepository.save(new ChapterAnalysisEntity(chapter));

        LocalDateTime now = LocalDateTime.now();
        int claimed = chapterAnalysisRepository.claimAnalysisLease(
                chapter.getId(),
                now,
                now.plusMinutes(6),
                "analysis-worker-a",
                ChapterAnalysisStatus.PENDING,
                ChapterAnalysisStatus.GENERATING
        );
        assertEquals(1, claimed);

        ChapterAnalysisEntity claimedEntity = chapterAnalysisRepository.findById(analysis.getId()).orElseThrow();
        assertEquals(ChapterAnalysisStatus.GENERATING, claimedEntity.getStatus());
        assertEquals("analysis-worker-a", claimedEntity.getLeaseOwner());
        assertNotNull(claimedEntity.getLeaseExpiresAt());

        int secondClaim = chapterAnalysisRepository.claimAnalysisLease(
                chapter.getId(),
                now.plusMinutes(1),
                now.plusMinutes(7),
                "analysis-worker-b",
                ChapterAnalysisStatus.PENDING,
                ChapterAnalysisStatus.GENERATING
        );
        assertEquals(0, secondClaim);

        claimedEntity.setLeaseExpiresAt(now.minusMinutes(1));
        chapterAnalysisRepository.save(claimedEntity);

        int staleReclaim = chapterAnalysisRepository.claimAnalysisLease(
                chapter.getId(),
                now.plusMinutes(2),
                now.plusMinutes(8),
                "analysis-worker-b",
                ChapterAnalysisStatus.PENDING,
                ChapterAnalysisStatus.GENERATING
        );
        assertEquals(1, staleReclaim);

        ChapterAnalysisEntity reclaimedEntity = chapterAnalysisRepository.findById(analysis.getId()).orElseThrow();
        assertEquals("analysis-worker-b", reclaimedEntity.getLeaseOwner());
    }

    @Test
    void analysisClaim_blocksUntilRetryWindowExpires() {
        ChapterEntity chapter = persistChapter("book-analysis-retry");
        ChapterAnalysisEntity analysis = new ChapterAnalysisEntity(chapter);
        analysis.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        analysis = chapterAnalysisRepository.save(analysis);

        LocalDateTime now = LocalDateTime.now();
        int earlyClaim = chapterAnalysisRepository.claimAnalysisLease(
                chapter.getId(),
                now,
                now.plusMinutes(6),
                "analysis-worker-a",
                ChapterAnalysisStatus.PENDING,
                ChapterAnalysisStatus.GENERATING
        );
        assertEquals(0, earlyClaim);

        int dueClaim = chapterAnalysisRepository.claimAnalysisLease(
                chapter.getId(),
                now.plusMinutes(6),
                now.plusMinutes(12),
                "analysis-worker-a",
                ChapterAnalysisStatus.PENDING,
                ChapterAnalysisStatus.GENERATING
        );
        assertEquals(1, dueClaim);
    }

    @Test
    void recapClaim_blocksUntilRetryWindowExpires() {
        ChapterEntity chapter = persistChapter("book-recap-retry");
        ChapterRecapEntity recap = new ChapterRecapEntity(chapter);
        recap.setStatus(ChapterRecapStatus.PENDING);
        recap.setNextRetryAt(LocalDateTime.now().plusMinutes(2));
        chapterRecapRepository.save(recap);

        LocalDateTime now = LocalDateTime.now();
        int earlyClaim = chapterRecapRepository.claimGenerationLease(
                chapter.getId(),
                now,
                now.plusMinutes(10),
                "recap-worker-a",
                ChapterRecapStatus.PENDING,
                ChapterRecapStatus.GENERATING
        );
        assertEquals(0, earlyClaim);

        int dueClaim = chapterRecapRepository.claimGenerationLease(
                chapter.getId(),
                now.plusMinutes(3),
                now.plusMinutes(13),
                "recap-worker-a",
                ChapterRecapStatus.PENDING,
                ChapterRecapStatus.GENERATING
        );
        assertEquals(1, dueClaim);
    }

    private ChapterEntity persistChapter(String sourceId) {
        BookEntity book = new BookEntity("Test Title", "Test Author", "manual");
        book.setSourceId(sourceId);
        book = bookRepository.save(book);

        ChapterEntity chapter = new ChapterEntity(1, "Chapter 1");
        chapter.setBook(book);
        return chapterRepository.save(chapter);
    }
}
