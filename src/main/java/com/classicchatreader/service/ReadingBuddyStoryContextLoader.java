package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Loads a position-bounded paragraph window for reading-buddy STORY CONTEXT.
 * Never includes paragraphs after the reader's current chapter/paragraph index.
 */
@Component
public class ReadingBuddyStoryContextLoader {

    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;
    private final ReadingBuddyProperties properties;

    public ReadingBuddyStoryContextLoader(
            ChapterRepository chapterRepository,
            ParagraphRepository paragraphRepository,
            ReadingBuddyProperties properties) {
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        this.properties = properties;
    }

    /**
     * Loads STORY CONTEXT text for the given book position.
     * Only paragraphs in the current chapter with {@code paragraphIndex <= readerParagraphIndex}
     * are considered (never future paragraphs or later chapters).
     */
    public String loadStoryContext(String bookId, int readerChapterIndex, int readerParagraphIndex) {
        if (bookId == null || bookId.isBlank() || readerChapterIndex < 0 || readerParagraphIndex < 0) {
            return "";
        }

        Optional<ChapterEntity> chapterOpt =
                chapterRepository.findByBookIdAndChapterIndex(bookId, readerChapterIndex);
        if (chapterOpt.isEmpty()) {
            return "";
        }

        ChapterEntity chapter = chapterOpt.get();
        List<ParagraphEntity> paragraphs =
                paragraphRepository.findByChapterIdOrderByParagraphIndex(chapter.getId());
        if (paragraphs == null || paragraphs.isEmpty()) {
            return "";
        }

        ReadingBuddyProperties.StoryContext cfg = properties.getStoryContext();
        List<ParagraphEntity> window = selectParagraphWindow(
                paragraphs,
                readerParagraphIndex,
                Math.max(0, cfg.getPriorParagraphs()),
                cfg.isIncludeChapterFirstParagraph());

        return formatAndCap(window, readerParagraphIndex, Math.max(1, cfg.getMaxSourceChars()));
    }

    /**
     * Pure selection of paragraph entities at or before the current index.
     * Includes: current, up to {@code priorParagraphs} previous, and optionally chapter first.
     * Never selects paragraphs with index &gt; {@code currentParagraphIndex}.
     */
    public static List<ParagraphEntity> selectParagraphWindow(
            List<ParagraphEntity> orderedParagraphs,
            int currentParagraphIndex,
            int priorParagraphs,
            boolean includeChapterFirstParagraph) {
        if (orderedParagraphs == null || orderedParagraphs.isEmpty() || currentParagraphIndex < 0) {
            return List.of();
        }

        List<ParagraphEntity> reachable = orderedParagraphs.stream()
                .filter(p -> p != null && p.getParagraphIndex() <= currentParagraphIndex)
                .sorted(Comparator.comparingInt(ParagraphEntity::getParagraphIndex))
                .toList();
        if (reachable.isEmpty()) {
            return List.of();
        }

        Set<Integer> selectedIndexes = new LinkedHashSet<>();

        Optional<ParagraphEntity> current = reachable.stream()
                .filter(p -> p.getParagraphIndex() == currentParagraphIndex)
                .findFirst();
        current.ifPresent(p -> selectedIndexes.add(p.getParagraphIndex()));

        // Prefer exact current; if missing (gap), use the latest reachable as "current" anchor.
        int anchorIndex = current.map(ParagraphEntity::getParagraphIndex)
                .orElse(reachable.get(reachable.size() - 1).getParagraphIndex());
        selectedIndexes.add(anchorIndex);

        for (int back = 1; back <= priorParagraphs; back++) {
            int target = anchorIndex - back;
            if (target < 0) {
                break;
            }
            boolean exists = reachable.stream().anyMatch(p -> p.getParagraphIndex() == target);
            if (exists) {
                selectedIndexes.add(target);
            }
        }

        if (includeChapterFirstParagraph) {
            ParagraphEntity first = reachable.get(0);
            if (first.getParagraphIndex() <= currentParagraphIndex) {
                selectedIndexes.add(first.getParagraphIndex());
            }
        }

        return reachable.stream()
                .filter(p -> selectedIndexes.contains(p.getParagraphIndex()))
                .sorted(Comparator.comparingInt(ParagraphEntity::getParagraphIndex))
                .toList();
    }

    /**
     * Formats selected paragraphs and enforces a max character budget.
     * Priority when truncating: keep current paragraph, then nearest prior, then chapter opener.
     */
    public static String formatAndCap(
            List<ParagraphEntity> window,
            int currentParagraphIndex,
            int maxSourceChars) {
        if (window == null || window.isEmpty() || maxSourceChars <= 0) {
            return "";
        }

        // Emit in reading order; allocate budget from current backwards if needed.
        List<ParagraphEntity> ordered = window.stream()
                .sorted(Comparator.comparingInt(ParagraphEntity::getParagraphIndex))
                .toList();

        // Build blocks with labels
        List<String> blocks = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (ParagraphEntity p : ordered) {
            String content = p.getContent() == null ? "" : p.getContent().trim();
            if (content.isBlank()) {
                continue;
            }
            String label = paragraphLabel(p.getParagraphIndex(), currentParagraphIndex);
            blocks.add(label + "\n" + content);
            indexes.add(p.getParagraphIndex());
        }
        if (blocks.isEmpty()) {
            return "";
        }

        // Prefer keeping later (nearer current) blocks when over budget.
        int total = blocks.stream().mapToInt(String::length).sum() + Math.max(0, blocks.size() - 1) * 2;
        if (total <= maxSourceChars) {
            return String.join("\n\n", blocks);
        }

        // Greedy from the end (current) toward the start.
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            String block = blocks.get(i);
            int separator = kept.isEmpty() ? 0 : 2;
            if (used + separator + block.length() <= maxSourceChars) {
                kept.add(0, block);
                used += separator + block.length();
            } else if (kept.isEmpty()) {
                // Always try to include a truncated current/last block.
                int remaining = maxSourceChars;
                kept.add(truncateBlock(block, remaining));
                break;
            }
            // else drop older block
        }
        return String.join("\n\n", kept);
    }

    private static String paragraphLabel(int paragraphIndex, int currentParagraphIndex) {
        if (paragraphIndex == currentParagraphIndex) {
            return "[Current paragraph " + paragraphIndex + "]:";
        }
        if (paragraphIndex == 0) {
            return "[Chapter opener — paragraph 0]:";
        }
        return "[Prior paragraph " + paragraphIndex + "]:";
    }

    private static String truncateBlock(String block, int maxChars) {
        if (block.length() <= maxChars) {
            return block;
        }
        if (maxChars <= 3) {
            return block.substring(0, maxChars);
        }
        return block.substring(0, maxChars - 3).trim() + "...";
    }
}
