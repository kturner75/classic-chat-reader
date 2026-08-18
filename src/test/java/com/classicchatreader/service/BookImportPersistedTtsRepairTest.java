package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.gutendex.GutenbergContentParser;
import com.classicchatreader.gutendex.GutendexClient;
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
import com.classicchatreader.service.BookImportService.ImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookImportPersistedTtsRepairTest {

    @Mock private GutendexClient gutendexClient;
    @Mock private GutenbergContentParser contentParser;
    @Mock private BookRepository bookRepository;
    @Mock private BookCoverRepository bookCoverRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private ChapterAnalysisRepository chapterAnalysisRepository;
    @Mock private ChapterRecapRepository chapterRecapRepository;
    @Mock private ChapterQuizRepository chapterQuizRepository;
    @Mock private IllustrationRepository illustrationRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private QuizAttemptRepository quizAttemptRepository;
    @Mock private QuizTrophyRepository quizTrophyRepository;
    @Mock private SearchService searchService;
    @Mock private MlaCitationFormatter mlaCitationFormatter;

    private BookEntity stored;
    private BookImportService bookImportService;
    private BookStorageService bookStorageService;

    @BeforeEach
    void setUp() {
        stored = new BookEntity("Romeo and Juliet", "William Shakespeare", "gutenberg");
        stored.setId("romeo-id");
        stored.setSourceId("1513");
        stored.setTtsEnabled(false);

        CuratedCatalogService curatedCatalogService = new CuratedCatalogService();
        bookStorageService = new BookStorageService(
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
        bookImportService = new BookImportService(
                gutendexClient,
                contentParser,
                bookStorageService,
                curatedCatalogService,
                "curated"
        );
    }

    @Test
    void importBook_repairsPersistedTtsFlagWhenCuratedDtoAlreadyLooksEnabled() {
        when(bookRepository.existsBySourceAndSourceId("gutenberg", "1513")).thenReturn(true);
        when(bookRepository.findBySourceAndSourceId("gutenberg", "1513")).thenReturn(Optional.of(stored));
        when(bookCoverRepository.findByBookId("romeo-id")).thenReturn(Optional.empty());
        when(bookRepository.findById("romeo-id")).thenReturn(Optional.of(stored));

        Book mapped = bookStorageService.findBySource("gutenberg", "1513").orElseThrow();
        assertTrue(mapped.ttsEnabled(), "DTO mapping reports TTS on for curated titles");
        assertFalse(stored.getTtsEnabled(), "stored column is still stale before re-import");

        ImportResult result = bookImportService.importBook(1513);

        assertFalse(result.success());
        assertEquals("romeo-id", result.bookId());
        assertTrue(stored.getTtsEnabled());
        verify(bookRepository).save(stored);
    }
}
