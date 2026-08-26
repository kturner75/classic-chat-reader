package com.classicchatreader.service;

import com.classicchatreader.entity.BookCoverEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.repository.BookCoverRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.IllustrationRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveAssetUploadServiceTest {

    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01
    };
    private static final byte[] JPEG = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    @Mock private BookCoverRepository bookCoverRepository;
    @Mock private BookRepository bookRepository;
    @Mock private IllustrationService illustrationService;
    @Mock private ComfyUIService comfyUIService;
    @Mock private BookCoverImageGeneratorService bookCoverImageGeneratorService;
    @Mock private IllustrationRepository illustrationRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private IllustrationPromptService promptService;
    @Mock private IllustrationStyleAnalysisService styleAnalysisService;
    @Mock private IllustrationImageGeneratorService illustrationImageGenerator;
    @Mock private CdnAssetService cdnAssetService;
    @Mock private CharacterRepository characterRepository;
    @Mock private ChapterAnalysisRepository chapterAnalysisRepository;
    @Mock private CharacterExtractionService extractionService;
    @Mock private CharacterPortraitService portraitService;
    @Mock private CharacterPortraitImageGeneratorService portraitImageGenerator;

    private BookCoverService bookCoverService;
    private IllustrationService illustrationWriteService;
    private CharacterService characterService;
    private BookEntity book;
    private ChapterEntity chapter;

    @BeforeEach
    void setUp() {
        bookCoverService = new BookCoverService(
                bookCoverRepository,
                bookRepository,
                illustrationService,
                comfyUIService,
                bookCoverImageGeneratorService,
                new AssetKeyService()
        );
        illustrationWriteService = new IllustrationService(
                illustrationRepository,
                chapterRepository,
                bookRepository,
                paragraphRepository,
                promptService,
                styleAnalysisService,
                comfyUIService,
                illustrationImageGenerator,
                new AssetKeyService(),
                cdnAssetService
        );
        characterService = new CharacterService(
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

        book = new BookEntity("Pride and Prejudice", "Jane Austen", "gutenberg");
        book.setId("book-1");
        book.setSourceId("1342");
        chapter = new ChapterEntity(1, "Chapter II");
        chapter.setId("chapter-1");
        chapter.setBook(book);
    }

    @Test
    void saveUploadedCover_studioSourceAndPrompt_doesNotEnqueue() throws Exception {
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(comfyUIService.saveBookCoverImage(any(), any())).thenReturn(
                "books/gutenberg/1342/covers/cover.png");
        when(bookCoverRepository.findByBookId("book-1")).thenReturn(Optional.empty());

        LiveAssetWriteResult result = bookCoverService.saveUploadedCover(
                "book-1",
                PNG,
                "studio",
                "regency ballroom, no text",
                "edited cover prompt");

        assertEquals(LiveAssetWriteResult.SAVED, result);
        ArgumentCaptor<BookCoverEntity> captor = ArgumentCaptor.forClass(BookCoverEntity.class);
        verify(bookCoverRepository).save(captor.capture());
        BookCoverEntity saved = captor.getValue();
        assertEquals(IllustrationStatus.COMPLETED, saved.getStatus());
        assertEquals("studio", saved.getCoverSource());
        assertEquals("regency ballroom, no text", saved.getGeneratedPrompt());
        assertEquals("edited cover prompt", saved.getPromptOverride());
        assertEquals(0, bookCoverService.getQueueDepth());
        verify(bookCoverImageGeneratorService, never()).generateBookCover(any(), any(), any());
    }

    @Test
    void saveUploadedCover_omittedSource_stampsManualUpload() throws Exception {
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(comfyUIService.saveBookCoverImage(any(), any())).thenReturn(
                "books/gutenberg/1342/covers/cover.png");
        when(bookCoverRepository.findByBookId("book-1")).thenReturn(Optional.empty());

        bookCoverService.saveUploadedCover("book-1", PNG, null, null, null);

        ArgumentCaptor<BookCoverEntity> captor = ArgumentCaptor.forClass(BookCoverEntity.class);
        verify(bookCoverRepository).save(captor.capture());
        assertEquals("manual_upload", captor.getValue().getCoverSource());
        assertNull(captor.getValue().getGeneratedPrompt());
    }

    @Test
    void saveUploadedCover_cacheOnly_doesNotWrite() throws Exception {
        ReflectionTestUtils.setField(bookCoverService, "cacheOnly", true);

        assertEquals(LiveAssetWriteResult.CACHE_ONLY,
                bookCoverService.saveUploadedCover("book-1", PNG, "studio", "prompt", null));

        verify(bookCoverRepository, never()).save(any());
        verify(bookCoverImageGeneratorService, never()).generateBookCover(any(), any(), any());
    }

    @Test
    void saveUploadedCover_nonPng_rejected() throws Exception {
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));

        assertThrows(UnsupportedImageTypeException.class,
                () -> bookCoverService.saveUploadedCover("book-1", JPEG, "studio", null, null));

        verify(bookCoverRepository, never()).save(any());
        verify(bookCoverImageGeneratorService, never()).generateBookCover(any(), any(), any());
    }

    @Test
    void saveUploadedIllustration_writesBytesAndPromptWithoutEnqueue() throws Exception {
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(comfyUIService.saveIllustrationImage(any(), any())).thenReturn(
                "books/gutenberg/1342/illustrations/chapters/1.png");
        when(illustrationRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());

        LiveAssetWriteResult result = illustrationWriteService.saveUploadedIllustration(
                "chapter-1",
                PNG,
                "studio",
                "storm over the lake",
                null);

        assertEquals(LiveAssetWriteResult.SAVED, result);
        ArgumentCaptor<IllustrationEntity> captor = ArgumentCaptor.forClass(IllustrationEntity.class);
        verify(illustrationRepository).save(captor.capture());
        IllustrationEntity saved = captor.getValue();
        assertEquals(IllustrationStatus.COMPLETED, saved.getStatus());
        assertEquals("storm over the lake", saved.getGeneratedPrompt());
        assertEquals(0, illustrationWriteService.getQueueDepth());
        verify(illustrationImageGenerator, never()).generateIllustration(any(), any(), any());
        verify(illustrationRepository, never()).claimGenerationLease(any(), any(), any(), any(), any(), any());
    }

    @Test
    void saveUploadedIllustration_cacheOnlyAndNonPng() throws Exception {
        ReflectionTestUtils.setField(illustrationWriteService, "cacheOnly", true);
        assertEquals(LiveAssetWriteResult.CACHE_ONLY,
                illustrationWriteService.saveUploadedIllustration("chapter-1", PNG, "studio", null, null));
        verify(illustrationRepository, never()).save(any());

        ReflectionTestUtils.setField(illustrationWriteService, "cacheOnly", false);
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        assertThrows(UnsupportedImageTypeException.class,
                () -> illustrationWriteService.saveUploadedIllustration("chapter-1", JPEG, "studio", null, null));
        verify(illustrationImageGenerator, never()).generateIllustration(any(), any(), any());
    }

    @Test
    void saveUploadedPortrait_writesBytesAndPromptWithoutEnqueue() throws Exception {
        CharacterEntity character = new CharacterEntity(book, "Mr. Bennet", "Elizabeth's father", chapter, 0);
        character.setId("character-1");
        character.setStatus(CharacterStatus.FAILED);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.savePortraitImage(any(), any())).thenReturn(
                "books/gutenberg/1342/portraits/characters/mr-bennet.png");

        LiveAssetWriteResult result = characterService.saveUploadedPortrait(
                "character-1",
                PNG,
                "studio",
                "elizabeth's father in a study",
                null);

        assertEquals(LiveAssetWriteResult.SAVED, result);
        ArgumentCaptor<CharacterEntity> captor = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characterRepository).save(captor.capture());
        CharacterEntity saved = captor.getValue();
        assertEquals(CharacterStatus.COMPLETED, saved.getStatus());
        assertEquals("elizabeth's father in a study", saved.getPortraitPrompt());
        assertEquals("books/gutenberg/1342/portraits/characters/mr-bennet.png", saved.getPortraitFilename());
        assertEquals(0, characterService.getQueueDepth());
        verify(portraitImageGenerator, never()).generatePortrait(any(), any(), any());
    }

    @Test
    void saveUploadedPortrait_cacheOnlyAndNonPng() throws Exception {
        ReflectionTestUtils.setField(characterService, "cacheOnly", true);
        assertEquals(LiveAssetWriteResult.CACHE_ONLY,
                characterService.saveUploadedPortrait("character-1", PNG, "studio", null, null));
        verify(characterRepository, never()).save(any());

        ReflectionTestUtils.setField(characterService, "cacheOnly", false);
        CharacterEntity character = new CharacterEntity(book, "Mr. Bennet", "Elizabeth's father", chapter, 0);
        character.setId("character-1");
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        assertThrows(UnsupportedImageTypeException.class,
                () -> characterService.saveUploadedPortrait("character-1", JPEG, "studio", null, null));
        verify(portraitImageGenerator, never()).generatePortrait(any(), any(), any());
    }
}
