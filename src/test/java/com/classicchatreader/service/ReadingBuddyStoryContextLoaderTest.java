package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBuddyStoryContextLoaderTest {

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private ParagraphRepository paragraphRepository;

    private ReadingBuddyProperties properties;
    private ReadingBuddyStoryContextLoader loader;

    @BeforeEach
    void setUp() {
        properties = new ReadingBuddyProperties();
        properties.getStoryContext().setMaxSourceChars(4000);
        properties.getStoryContext().setPriorParagraphs(2);
        properties.getStoryContext().setIncludeChapterFirstParagraph(true);
        loader = new ReadingBuddyStoryContextLoader(chapterRepository, paragraphRepository, properties);
    }

    @Test
    void selectParagraphWindow_neverIncludesFutureParagraphs() {
        List<ParagraphEntity> all = paragraphs(
                0, "First of chapter",
                1, "Second",
                2, "Third current",
                3, "FUTURE spoilers",
                4, "More future"
        );

        List<ParagraphEntity> window =
                ReadingBuddyStoryContextLoader.selectParagraphWindow(all, 2, 2, true);

        List<Integer> indexes = window.stream().map(ParagraphEntity::getParagraphIndex).toList();
        assertEquals(List.of(0, 1, 2), indexes);
        assertFalse(indexes.contains(3));
        assertFalse(indexes.contains(4));
    }

    @Test
    void selectParagraphWindow_includesCurrentAndPriorsAndOptionalFirst() {
        List<ParagraphEntity> all = paragraphs(
                0, "Opener",
                1, "A",
                2, "B",
                3, "C",
                4, "D current",
                5, "Future"
        );

        // prior=2 → 4,3,2 plus first 0
        List<ParagraphEntity> window =
                ReadingBuddyStoryContextLoader.selectParagraphWindow(all, 4, 2, true);

        assertEquals(List.of(0, 2, 3, 4),
                window.stream().map(ParagraphEntity::getParagraphIndex).toList());
    }

    @Test
    void selectParagraphWindow_withoutFirstParagraphFlag_skipsDistantOpener() {
        List<ParagraphEntity> all = paragraphs(
                0, "Opener",
                1, "A",
                2, "B",
                3, "C current"
        );

        List<ParagraphEntity> window =
                ReadingBuddyStoryContextLoader.selectParagraphWindow(all, 3, 1, false);

        assertEquals(List.of(2, 3),
                window.stream().map(ParagraphEntity::getParagraphIndex).toList());
    }

    @Test
    void selectParagraphWindow_atStart_onlyCurrent() {
        List<ParagraphEntity> all = paragraphs(0, "Only", 1, "Later");

        List<ParagraphEntity> window =
                ReadingBuddyStoryContextLoader.selectParagraphWindow(all, 0, 2, true);

        assertEquals(List.of(0),
                window.stream().map(ParagraphEntity::getParagraphIndex).toList());
    }

    @Test
    void selectParagraphWindow_sparseIndexes_walksNeighborsNotExactArithmetic() {
        // Gaps: 0, 2, 5, 9 — at 9 with prior=2 should take 5 and 2 (not miss due to 9-1/9-2)
        List<ParagraphEntity> all = paragraphs(
                0, "Opener",
                2, "Sparse A",
                5, "Sparse B",
                9, "Sparse current",
                12, "Future"
        );

        List<ParagraphEntity> window =
                ReadingBuddyStoryContextLoader.selectParagraphWindow(all, 9, 2, false);

        assertEquals(List.of(2, 5, 9),
                window.stream().map(ParagraphEntity::getParagraphIndex).toList());
    }

    @Test
    void selectParagraphWindow_missingExactCurrent_usesLatestReachableAsAnchor() {
        List<ParagraphEntity> all = paragraphs(
                0, "Opener",
                1, "A",
                2, "B latest",
                5, "Future"
        );

        // Request index 4 which does not exist; reachable max is 2
        List<ParagraphEntity> window =
                ReadingBuddyStoryContextLoader.selectParagraphWindow(all, 4, 1, false);

        assertEquals(List.of(1, 2),
                window.stream().map(ParagraphEntity::getParagraphIndex).toList());

        String formatted = ReadingBuddyStoryContextLoader.formatAndCap(window, 4, 4000);
        assertTrue(formatted.contains("[Current paragraph 2]:"));
        assertTrue(formatted.contains("B latest"));
        assertFalse(formatted.contains("Future"));
    }

    @Test
    void formatAndCap_labelsCurrentAndPrefersCurrentWhenOverBudget() {
        // Distinctive fragments so we can prove prioritization under a tight budget.
        String priorA = "AAA_ONLY_PRIOR_OPENER";
        String priorB = "BBB_ONLY_PRIOR_MID";
        String current = "CCC_ONLY_CURRENT_UNIQUE";
        List<ParagraphEntity> window = paragraphs(
                0, priorA,
                1, priorB,
                2, current
        );

        String formatted = ReadingBuddyStoryContextLoader.formatAndCap(window, 2, 4000);
        assertTrue(formatted.contains("[Current paragraph 2]:"));
        assertTrue(formatted.contains(current));
        assertTrue(formatted.contains("[Chapter opener — paragraph 0]:"));

        // Budget large enough for current block alone, too small for current + a prior.
        // Current block ≈ "[Current paragraph 2]:\n" (24) + content (23) = ~47 chars.
        int currentBlockLen = ("[Current paragraph 2]:\n" + current).length();
        int budget = currentBlockLen + 10; // not enough for another full prior block
        String tiny = ReadingBuddyStoryContextLoader.formatAndCap(window, 2, budget);

        assertTrue(tiny.length() <= budget);
        assertTrue(tiny.contains("CCC_ONLY_CURRENT_UNIQUE"),
                "tight budget must keep distinctive current fragment: " + tiny);
        assertFalse(tiny.contains("AAA_ONLY_PRIOR_OPENER"),
                "tight budget should drop distant prior: " + tiny);
    }

    @Test
    void loadStoryContext_usesRepositoryAndBoundsToPosition() {
        ChapterEntity chapter = new ChapterEntity(1, "Chapter Two");
        chapter.setId("ch-1");
        when(chapterRepository.findByBookIdAndChapterIndex("book-1", 1)).thenReturn(Optional.of(chapter));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("ch-1")).thenReturn(paragraphs(
                0, "Opener text about manners.",
                1, "He bowed stiffly.",
                2, "She considered the slight.",
                3, "FUTURE: they marry at the end."
        ));

        String context = loader.loadStoryContext("book-1", 1, 2);

        assertTrue(context.contains("She considered the slight."));
        assertTrue(context.contains("He bowed stiffly."));
        assertFalse(context.contains("they marry at the end"));
        assertFalse(context.contains("FUTURE"));
    }

    @Test
    void loadStoryContext_missingChapter_returnsEmpty() {
        when(chapterRepository.findByBookIdAndChapterIndex("book-1", 9)).thenReturn(Optional.empty());
        assertEquals("", loader.loadStoryContext("book-1", 9, 0));
    }

    private static List<ParagraphEntity> paragraphs(Object... indexAndContent) {
        // pairs: int index, String content
        List<ParagraphEntity> list = new java.util.ArrayList<>();
        for (int i = 0; i < indexAndContent.length; i += 2) {
            int idx = (Integer) indexAndContent[i];
            String content = (String) indexAndContent[i + 1];
            list.add(new ParagraphEntity(idx, content));
        }
        return list.stream()
                .sorted((a, b) -> Integer.compare(a.getParagraphIndex(), b.getParagraphIndex()))
                .collect(Collectors.toList());
    }
}
