package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import org.springframework.stereotype.Service;

@Service
public class AssetKeyService {

    private static final int MAX_SEGMENT_LENGTH = 64;
    private static final int MAX_KEY_LENGTH = 255;

    public String buildBookKey(BookEntity book) {
        String source = normalizeSegment(book.getSource());
        String sourceId = normalizeSegment(book.getSourceId());
        if (!source.isBlank() && !sourceId.isBlank()) {
            return "books/" + source + "/" + sourceId;
        }
        return "books/local/" + normalizeSegment(book.getId());
    }

    public String buildIllustrationKey(ChapterEntity chapter) {
        BookEntity book = chapter.getBook();
        return buildBookKey(book)
                + "/illustrations/chapters/"
                + chapter.getChapterIndex()
                + ".png";
    }

    public String buildPortraitKey(BookEntity book, String characterSlug) {
        String base = buildBookKey(book) + "/portraits/characters/";
        String normalizedSlug = normalizeSegment(characterSlug);
        if (normalizedSlug.isBlank()) {
            normalizedSlug = "character";
        }
        int maxSlugLength = Math.max(16, MAX_KEY_LENGTH - base.length() - ".png".length());
        if (normalizedSlug.length() > maxSlugLength) {
            normalizedSlug = normalizedSlug.substring(0, maxSlugLength);
            normalizedSlug = normalizedSlug.replaceAll("-+$", "");
        }
        return base + normalizedSlug + ".png";
    }

    public String buildAudioKey(BookEntity book, String voice, int chapterIndex, int paragraphIndex) {
        String voiceSegment = normalizeSegment(voice);
        if (voiceSegment.isBlank()) {
            voiceSegment = "default";
        }
        return buildBookKey(book)
                + "/audio/"
                + voiceSegment
                + "/chapters/"
                + chapterIndex
                + "/"
                + paragraphIndex
                + ".mp3";
    }

    public String normalizeCharacterName(String name) {
        return normalizeSegment(name);
    }

    public String normalizeSegment(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        if (normalized.length() > MAX_SEGMENT_LENGTH) {
            normalized = normalized.substring(0, MAX_SEGMENT_LENGTH);
            normalized = normalized.replaceAll("-+$", "");
        }
        return normalized;
    }
}
