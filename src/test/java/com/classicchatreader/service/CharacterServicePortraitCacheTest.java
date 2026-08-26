package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServicePortraitCacheTest {

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
    private CharacterEntity character;

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
        ReflectionTestUtils.setField(service, "workerId", "test-worker");
        ReflectionTestUtils.setField(service, "portraitLeaseMinutes", 20);

        BookEntity book = new BookEntity();
        book.setId("book-1");
        book.setTitle("Pride and Prejudice");
        book.setSource("gutenberg");
        book.setSourceId("1342");

        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setBook(book);
        chapter.setChapterIndex(1);

        character = new CharacterEntity(book, "Mr. Bennet", "Elizabeth's father", chapter, 0);
        character.setId("character-1");
        character.setStatus(CharacterStatus.FAILED);
        character.setErrorMessage("Connection refused");
        character.setRetryCount(3);
    }

    @Test
    void generationRestoresStableCachedPortraitWithoutCallingComfyUi() throws Exception {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        when(characterRepository.claimPortraitLease(
                eq("character-1"), any(), any(), eq("test-worker"),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING)))
                .thenReturn(1);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(comfyUIService.hasPortraitImage(cacheKey)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "generatePortrait", "character-1");

        assertEquals(CharacterStatus.COMPLETED, character.getStatus());
        assertEquals(cacheKey, character.getPortraitFilename());
        assertEquals(0, character.getRetryCount());
        assertNull(character.getErrorMessage());
        verify(characterRepository).save(character);
        verify(comfyUIService, never()).submitPortraitWorkflow(any(), any(), any());
        verify(portraitService, never()).generatePortraitPrompt(any(), any(), any(), any(), any());
    }

    @Test
    void requestPortrait_queuesOneCharacterWithoutPrefetch() {
        character.setStatus(CharacterStatus.PENDING);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(any())).thenReturn(false);

        service.requestPortrait("character-1");

        assertEquals(1, service.getQueueDepth());
    }

    @Test
    void regeneratePortraitWithPrompt_resetsLiveSlotAndQueuesCustomPrompt() {
        character.setStatus(CharacterStatus.COMPLETED);
        character.setPortraitFilename("books/gutenberg/1342/portraits/characters/mr-bennet.png");
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));

        service.regeneratePortraitWithPrompt("character-1", "Mr. Bennet in a dark coat");

        assertEquals(CharacterStatus.PENDING, character.getStatus());
        assertEquals("Mr. Bennet in a dark coat", character.getPortraitPrompt());
        assertNull(character.getPortraitFilename());
        assertEquals(1, service.getQueueDepth());
        verify(characterRepository).save(character);
    }

    @Test
    void generatePortrait_customPrompt_skipsCacheRestoreAndPromptLlm() throws Exception {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        when(characterRepository.claimPortraitLease(
                eq("character-1"), any(), any(), eq("test-worker"),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING)))
                .thenReturn(1);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(portraitImageGenerator.generatePortrait(
                eq("Mr. Bennet in a dark coat"), eq("portrait_character-1"), eq(cacheKey)))
                .thenReturn(cacheKey);

        ReflectionTestUtils.invokeMethod(service, "generatePortrait", "character-1", "Mr. Bennet in a dark coat");

        verify(portraitService, never()).generatePortraitPrompt(any(), any(), any(), any(), any());
        verify(comfyUIService, never()).hasPortraitImage(any());
        verify(portraitImageGenerator).generatePortrait(
                "Mr. Bennet in a dark coat", "portrait_character-1", cacheKey);
    }

    @Test
    void secondaryCharacterIsNotChatEligibleWhenBookHasNoPrimary() {
        character.setCharacterType(com.classicchatreader.entity.CharacterType.SECONDARY);
        assertEquals(false, service.isChatEligible(character));
    }

    @Test
    void secondaryCharacterIsNotChatEligibleWhenPrimaryExists() {
        character.setCharacterType(com.classicchatreader.entity.CharacterType.SECONDARY);
        assertEquals(false, service.isChatEligible(character));
    }

    @Test
    void startupRecoveryRestoresFailedPortraitInCacheOnlyMode() throws Exception {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        ReflectionTestUtils.setField(service, "cacheOnly", true);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(comfyUIService.hasPortraitImage(cacheKey)).thenReturn(true);

        int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(CharacterStatus.COMPLETED, character.getStatus());
        assertEquals(cacheKey, character.getPortraitFilename());
        assertNull(character.getErrorMessage());
        verify(characterRepository).save(character);
        verify(comfyUIService, never()).submitPortraitWorkflow(any(), any(), any());
    }

    @Test
    void startupRecoveryRestoresUniqueHonorificAndSurnameAlias() throws Exception {
        character.setName("Mr. Bingley");
        String expectedKey = "books/gutenberg/1342/portraits/characters/mr-bingley.png";
        String cachedAlias = "books/gutenberg/1342/portraits/characters/mr-charles-bingley.png";
        ReflectionTestUtils.setField(service, "cacheOnly", true);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(comfyUIService.hasPortraitImage(expectedKey)).thenReturn(false);
        when(comfyUIService.listPortraitImages("books/gutenberg/1342/portraits/characters"))
                .thenReturn(List.of(
                        "books/gutenberg/1342/portraits/characters/caroline-bingley.png",
                        cachedAlias
                ));

        int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(CharacterStatus.COMPLETED, character.getStatus());
        assertEquals(cachedAlias, character.getPortraitFilename());
        assertNull(character.getErrorMessage());
    }
}
