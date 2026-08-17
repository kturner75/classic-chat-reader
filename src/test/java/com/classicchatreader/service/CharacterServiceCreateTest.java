package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServiceCreateTest {

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

        book = new BookEntity();
        book.setId("book-northanger");
        book.setTitle("Northanger Abbey");
        chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setBook(book);
        chapter.setChapterIndex(0);
        chapter.setTitle("Chapter 1");
    }

    @Test
    void createCharacter_skipsSameIdentityKeyOnly() {
        CharacterEntity existing = persisted("char-1", "Sally", CharacterType.SECONDARY, CharacterStatus.COMPLETED);
        when(characterRepository.findByBookIdAndNameKey("book-northanger", "sally")).thenReturn(List.of());
        when(characterRepository.findAllByBookIdAndNameIgnoreCase("book-northanger", "Sally."))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdOrderByCreatedAt("book-northanger")).thenReturn(List.of(existing));

        assertNull(service.createCharacter(book, chapter, "Sally.", "A sister.", 4));
        verify(characterRepository, never()).saveAndFlush(any());
    }

    @Test
    void createCharacter_doesNotCollapseMrsBennetAndElizabethBennet() {
        CharacterEntity mrsBennet = persisted("char-mrs", "Mrs. Bennet", CharacterType.PRIMARY, CharacterStatus.COMPLETED);
        when(characterRepository.findByBookIdAndNameKey("book-northanger", "elizabeth bennet")).thenReturn(List.of());
        when(characterRepository.findAllByBookIdAndNameIgnoreCase("book-northanger", "Elizabeth Bennet"))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdOrderByCreatedAt("book-northanger")).thenReturn(List.of(mrsBennet));
        when(characterRepository.saveAndFlush(any(CharacterEntity.class))).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("char-elizabeth");
            return saved;
        });

        CharacterEntity created = service.createCharacter(
                book, chapter, "Elizabeth Bennet", "The second Bennet daughter.", 6);

        assertEquals("char-elizabeth", created.getId());
        assertEquals("elizabeth bennet", created.getNameKey());
    }

    @Test
    void createCharacter_doesNotCollapseMrAllenAndMrsAllen() {
        CharacterEntity mrAllen = persisted("char-mr", "Mr. Allen", CharacterType.SECONDARY, CharacterStatus.COMPLETED);
        when(characterRepository.findByBookIdAndNameKey("book-northanger", "mrs allen")).thenReturn(List.of());
        when(characterRepository.findAllByBookIdAndNameIgnoreCase("book-northanger", "Mrs. Allen"))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdOrderByCreatedAt("book-northanger")).thenReturn(List.of(mrAllen));
        when(characterRepository.saveAndFlush(any(CharacterEntity.class))).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("char-mrs");
            return saved;
        });

        CharacterEntity created = service.createCharacter(
                book, chapter, "Mrs. Allen", "Catherine's Bath chaperone.", 2);

        assertEquals("char-mrs", created.getId());
        assertEquals("mrs allen", created.getNameKey());
    }

    @Test
    void createCharacter_doesNotCollapseUnicodeOrNumericLookalikes() {
        CharacterEntity jose = persisted("char-jose", "José", CharacterType.SECONDARY, CharacterStatus.COMPLETED);
        when(characterRepository.findByBookIdAndNameKey("book-northanger", "jos")).thenReturn(List.of());
        when(characterRepository.findAllByBookIdAndNameIgnoreCase("book-northanger", "Jos"))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdOrderByCreatedAt("book-northanger")).thenReturn(List.of(jose));
        when(characterRepository.saveAndFlush(any(CharacterEntity.class))).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("char-jos");
            return saved;
        });

        CharacterEntity created = service.createCharacter(book, chapter, "Jos", "A different person.", 3);

        assertEquals("char-jos", created.getId());
        assertEquals("jos", created.getNameKey());
        assertEquals("josé", jose.getNameKey());
    }

    @Test
    void createCharacter_insertsOnceForFreshName() {
        when(characterRepository.findByBookIdAndNameKey("book-northanger", "henry tilney")).thenReturn(List.of());
        when(characterRepository.findAllByBookIdAndNameIgnoreCase("book-northanger", "Henry Tilney"))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdOrderByCreatedAt("book-northanger")).thenReturn(List.of());
        when(characterRepository.saveAndFlush(any(CharacterEntity.class))).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("char-new");
            return saved;
        });

        CharacterEntity created = service.createCharacter(book, chapter, "  Henry Tilney  ", "A clergyman.", 2);

        ArgumentCaptor<CharacterEntity> captor = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characterRepository).saveAndFlush(captor.capture());
        assertEquals("Henry Tilney", captor.getValue().getName());
        assertEquals("henry tilney", captor.getValue().getNameKey());
        assertEquals("char-new", created.getId());
    }

    @Test
    void upsertCharacter_adoptsExistingRowWhenUniqueConstraintRaces() {
        CharacterEntity winner = persisted("char-1", "Sally", CharacterType.SECONDARY, CharacterStatus.PENDING);
        when(characterRepository.findByBookIdAndNameKey("book-northanger", "sally"))
                .thenReturn(List.of())
                .thenReturn(List.of(winner));
        when(characterRepository.findAllByBookIdAndNameIgnoreCase("book-northanger", "sally"))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdOrderByCreatedAt("book-northanger")).thenReturn(List.of());
        when(characterRepository.saveAndFlush(any(CharacterEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk_characters_book_name_key"));

        CharacterService.CharacterUpsert upsert = service.upsertCharacter(
                book, chapter, "sally", "Catherine's sister.", 1, CharacterType.PRIMARY);

        assertEquals(winner, upsert.character());
        assertTrue(upsert.promoted());
        verify(characterRepository).save(winner);
        assertEquals(CharacterType.PRIMARY, winner.getCharacterType());
    }

    @Test
    void getCharactersForBook_hidesResidualNormalizedDuplicates() {
        CharacterEntity first = persisted("char-1", "Sally", CharacterType.SECONDARY, CharacterStatus.PENDING);
        CharacterEntity duplicate = persisted("char-2", "Sally.", CharacterType.SECONDARY, CharacterStatus.COMPLETED);
        CharacterEntity tilney = persisted("char-3", "Henry Tilney", CharacterType.PRIMARY, CharacterStatus.COMPLETED);
        when(characterRepository.findByBookIdOrderByCreatedAt("book-northanger"))
                .thenReturn(List.of(first, duplicate, tilney));
        when(characterRepository.countByBookIdAndCharacterType("book-northanger", CharacterType.PRIMARY))
                .thenReturn(1L);

        var infos = service.getCharactersForBook("book-northanger");

        assertEquals(List.of("Sally.", "Henry Tilney"), infos.stream().map(info -> info.name()).toList());
        assertEquals(List.of("char-2", "char-3"), infos.stream().map(info -> info.id()).toList());
    }

    private CharacterEntity persisted(String id, String name, CharacterType type, CharacterStatus status) {
        CharacterEntity character = new CharacterEntity(book, name, "A character", chapter, 0);
        character.setId(id);
        character.setCharacterType(type);
        character.setStatus(status);
        character.setCreatedAt(LocalDateTime.of(2026, 8, 1, 12, 0));
        return character;
    }
}
