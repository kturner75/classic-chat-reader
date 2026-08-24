package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterAnalysisEntity;
import com.classicchatreader.entity.ChapterAnalysisStatus;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServiceMinerTest {

    @Mock private CharacterRepository characterRepository;
    @Mock private ChapterAnalysisRepository chapterAnalysisRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private BookRepository bookRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private CharacterExtractionService extractionService;
    @Mock private CharacterPortraitService portraitService;
    @Mock private IllustrationService illustrationService;
    @Mock private ComfyUIService comfyUIService;
    @Mock private CharacterPortraitImageGeneratorService portraitImageGenerator;

    private CharacterService service;
    private BookEntity book;
    private ChapterEntity chapter;

    @BeforeEach
    void setUp() {
        service = new CharacterService(
                characterRepository,
                chapterAnalysisRepository,
                chapterRepository,
                bookRepository,
                paragraphRepository,
                extractionService,
                portraitService,
                illustrationService,
                comfyUIService,
                portraitImageGenerator,
                new AssetKeyService()
        );
        service.setSelf(service);
        ReflectionTestUtils.setField(service, "cacheOnly", false);
        ReflectionTestUtils.setField(service, "workerId", "test-worker");
        ReflectionTestUtils.setField(service, "analysisLeaseMinutes", 15);

        book = new BookEntity();
        book.setId("book-84");
        book.setTitle("Frankenstein");
        book.setAuthor("Mary Wollstonecraft Shelley");

        chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setChapterIndex(0);
        chapter.setTitle("Letter I");
        chapter.setBook(book);
    }

    @Test
    void minerDoesNotInsertSecondaryForNamesNotAlreadyTrusted() {
        CharacterEntity victor = new CharacterEntity(
                book, "Victor Frankenstein", "A Genevese student", chapter, 3, CharacterType.PRIMARY);
        victor.setId("character-victor");

        when(chapterAnalysisRepository.claimAnalysisLease(
                eq("chapter-1"), any(), any(), eq("test-worker"),
                eq(ChapterAnalysisStatus.PENDING), eq(ChapterAnalysisStatus.GENERATING)))
                .thenReturn(1);
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt("book-84")).thenReturn(List.of(victor));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenReturn(List.of(paragraph(0, "Justine Moritz entered the cottage.")));
        when(chapterAnalysisRepository.findByChapterId("chapter-1"))
                .thenReturn(Optional.of(new ChapterAnalysisEntity(chapter)));

        ReflectionTestUtils.invokeMethod(service, "processChapterAnalysis", "chapter-1");

        verify(extractionService, never()).extractCharactersFromChapter(
                anyString(), anyString(), anyString(), anyString(), any());
        verify(characterRepository, never()).save(any(CharacterEntity.class));
        ArgumentCaptor<ChapterAnalysisEntity> analysis = ArgumentCaptor.forClass(ChapterAnalysisEntity.class);
        verify(chapterAnalysisRepository).save(analysis.capture());
        assertThat(analysis.getValue().getCharacterCount()).isZero();
    }

    @Test
    void minerDoesNotInventWhenRosterIsEmpty() {
        when(chapterAnalysisRepository.claimAnalysisLease(
                eq("chapter-1"), any(), any(), eq("test-worker"),
                eq(ChapterAnalysisStatus.PENDING), eq(ChapterAnalysisStatus.GENERATING)))
                .thenReturn(1);
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt("book-84")).thenReturn(List.of());
        when(chapterAnalysisRepository.findByChapterId("chapter-1"))
                .thenReturn(Optional.of(new ChapterAnalysisEntity(chapter)));

        ReflectionTestUtils.invokeMethod(service, "processChapterAnalysis", "chapter-1");

        verify(extractionService, never()).extractCharactersFromChapter(
                anyString(), anyString(), anyString(), anyString(), any());
        verify(characterRepository, never()).save(any(CharacterEntity.class));
        verify(paragraphRepository, never()).findByChapterIdOrderByParagraphIndex(any());
    }

    @Test
    void refineTrustedFirstAppearancesWorksWithoutOpenSession() {
        ChapterEntity laterChapter = new ChapterEntity();
        laterChapter.setId("chapter-5");
        laterChapter.setChapterIndex(4);
        laterChapter.setTitle("Chapter V");

        CharacterEntity victor = new CharacterEntity(
                book, "Victor Frankenstein", "A Genevese student", laterChapter, 0, CharacterType.PRIMARY);
        victor.setId("character-victor");

        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenReturn(List.of(paragraph(2, "Victor Frankenstein was born in Naples.")));
        when(characterRepository.save(victor)).thenReturn(victor);

        int updated = ReflectionTestUtils.invokeMethod(
                service, "refineTrustedFirstAppearances", chapter, List.of(victor));

        assertThat(updated).isEqualTo(1);
        assertThat(victor.getFirstChapter()).isSameAs(chapter);
        assertThat(victor.getFirstParagraphIndex()).isEqualTo(2);
        verify(characterRepository).save(victor);
    }

    private static ParagraphEntity paragraph(int index, String content) {
        ParagraphEntity paragraph = new ParagraphEntity(index, content);
        paragraph.setId("p-" + index);
        return paragraph;
    }
}
