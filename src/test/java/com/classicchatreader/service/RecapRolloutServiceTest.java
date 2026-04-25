package com.classicchatreader.service;

import com.classicchatreader.entity.ChapterRecapStatus;
import com.classicchatreader.repository.ChapterRecapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecapRolloutServiceTest {

    @Mock
    private ChapterRecapRepository chapterRecapRepository;

    private RecapRolloutService recapRolloutService;

    @BeforeEach
    void setUp() {
        recapRolloutService = new RecapRolloutService(chapterRecapRepository);
    }

    @Test
    void allMode_allowsAnyBook() {
        ReflectionTestUtils.setField(recapRolloutService, "rolloutMode", "all");
        ReflectionTestUtils.setField(recapRolloutService, "allowedBookIdsRaw", "");
        ReflectionTestUtils.invokeMethod(recapRolloutService, "init");

        assertTrue(recapRolloutService.isBookAllowed("book-1"));
    }

    @Test
    void allowListMode_onlyAllowsConfiguredBooks() {
        ReflectionTestUtils.setField(recapRolloutService, "rolloutMode", "allow-list");
        ReflectionTestUtils.setField(recapRolloutService, "allowedBookIdsRaw", "book-1, book-2");
        ReflectionTestUtils.invokeMethod(recapRolloutService, "init");

        assertTrue(recapRolloutService.isBookAllowed("book-1"));
        assertFalse(recapRolloutService.isBookAllowed("book-3"));
    }

    @Test
    void preGeneratedMode_checksForCompletedRecaps() {
        ReflectionTestUtils.setField(recapRolloutService, "rolloutMode", "pre-generated");
        ReflectionTestUtils.setField(recapRolloutService, "allowedBookIdsRaw", "");
        ReflectionTestUtils.invokeMethod(recapRolloutService, "init");

        when(chapterRecapRepository.existsByChapterBookIdAndStatus("book-1", ChapterRecapStatus.COMPLETED))
                .thenReturn(true);
        when(chapterRecapRepository.existsByChapterBookIdAndStatus("book-2", ChapterRecapStatus.COMPLETED))
                .thenReturn(false);

        assertTrue(recapRolloutService.isBookAllowed("book-1"));
        assertFalse(recapRolloutService.isBookAllowed("book-2"));
    }
}
