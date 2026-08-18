package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.model.Book;
import com.classicchatreader.repository.BookCoverRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterQuizRepository;
import com.classicchatreader.repository.ChapterRecapRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.IllustrationRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookStorageServiceTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookCoverRepository bookCoverRepository;
    @Mock private ChapterAnalysisRepository chapterAnalysisRepository;
    @Mock private ChapterRecapRepository chapterRecapRepository;
    @Mock private ChapterQuizRepository chapterQuizRepository;
    @Mock private IllustrationRepository illustrationRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private QuizAttemptRepository quizAttemptRepository;
    @Mock private QuizTrophyRepository quizTrophyRepository;
    @Mock private SearchService searchService;
    @Mock private MlaCitationFormatter mlaCitationFormatter;
    @Mock private CuratedCatalogService curatedCatalogService;
    @Mock private ChapterRepository chapterRepository;

    private BookStorageService service;

    @BeforeEach
    void setUp() {
        service = new BookStorageService(
                bookRepository,
                bookCoverRepository,
                chapterRepository,
                chapterAnalysisRepository,
                chapterRecapRepository,
                chapterQuizRepository,
                illustrationRepository,
                characterRepository,
                quizAttemptRepository,
                quizTrophyRepository,
                searchService,
                mlaCitationFormatter,
                curatedCatalogService
        );
    }

    @Test
    void isTtsEnabled_storedFlagWinsEvenIfNotCurated() {
        BookEntity book = new BookEntity("Pride and Prejudice", "Jane Austen", "gutenberg");
        book.setSourceId("1342");
        book.setTtsEnabled(true);

        assertTrue(service.isTtsEnabled(book));
    }

    @Test
    void isTtsEnabled_curatedTitleWithoutStoredFlag_isEnabled() {
        BookEntity book = new BookEntity("Romeo and Juliet", "William Shakespeare", "gutenberg");
        book.setSourceId("1513");
        book.setTtsEnabled(false);
        when(curatedCatalogService.isCuratedGutenbergSource("gutenberg", "1513")).thenReturn(true);

        assertTrue(service.isTtsEnabled(book));
    }

    @Test
    void isTtsEnabled_nonCuratedTitleWithoutStoredFlag_staysDisabled() {
        BookEntity book = new BookEntity("Obscure Tract", "Unknown", "gutenberg");
        book.setSourceId("999999");
        book.setTtsEnabled(false);
        when(curatedCatalogService.isCuratedGutenbergSource("gutenberg", "999999")).thenReturn(false);

        assertFalse(service.isTtsEnabled(book));
    }

    @Test
    void getBook_reportsTtsEnabledForCuratedTitleWithStaleFlag() {
        BookEntity book = new BookEntity("Romeo and Juliet", "William Shakespeare", "gutenberg");
        book.setId("romeo-id");
        book.setSourceId("1513");
        book.setTtsEnabled(false);
        when(bookRepository.findById("romeo-id")).thenReturn(Optional.of(book));
        when(bookCoverRepository.findByBookId("romeo-id")).thenReturn(Optional.empty());
        when(curatedCatalogService.isCuratedGutenbergSource("gutenberg", "1513")).thenReturn(true);

        Optional<Book> dto = service.getBook("romeo-id");

        assertTrue(dto.isPresent());
        assertTrue(dto.get().ttsEnabled());
        assertTrue(dto.get().curated());
    }

    @Test
    void persistTtsEnabledIfStoredOff_writesWhenColumnIsStaleEvenIfEffectiveTtsIsOn() {
        BookEntity book = new BookEntity("Romeo and Juliet", "William Shakespeare", "gutenberg");
        book.setId("romeo-id");
        book.setSourceId("1513");
        book.setTtsEnabled(false);
        when(bookRepository.findById("romeo-id")).thenReturn(Optional.of(book));
        when(curatedCatalogService.isCuratedGutenbergSource("gutenberg", "1513")).thenReturn(true);
        assertTrue(service.isTtsEnabled(book));

        assertTrue(service.persistTtsEnabledIfStoredOff("romeo-id"));

        assertTrue(book.getTtsEnabled());
        verify(bookRepository).save(book);
    }

    @Test
    void persistTtsEnabledIfStoredOff_skipsWriteWhenColumnAlreadyTrue() {
        BookEntity book = new BookEntity("Pride and Prejudice", "Jane Austen", "gutenberg");
        book.setId("pride-id");
        book.setSourceId("1342");
        book.setTtsEnabled(true);
        when(bookRepository.findById("pride-id")).thenReturn(Optional.of(book));

        assertFalse(service.persistTtsEnabledIfStoredOff("pride-id"));

        verify(bookRepository, never()).save(book);
    }
}
