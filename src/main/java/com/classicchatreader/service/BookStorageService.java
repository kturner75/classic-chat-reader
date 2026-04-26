package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.Book;
import com.classicchatreader.model.ChapterContent;
import com.classicchatreader.model.Paragraph;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.BookCoverRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterQuizRepository;
import com.classicchatreader.repository.ChapterRecapRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.IllustrationRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class BookStorageService {

    private final BookRepository bookRepository;
    private final BookCoverRepository bookCoverRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterAnalysisRepository chapterAnalysisRepository;
    private final ChapterRecapRepository chapterRecapRepository;
    private final ChapterQuizRepository chapterQuizRepository;
    private final IllustrationRepository illustrationRepository;
    private final CharacterRepository characterRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final QuizTrophyRepository quizTrophyRepository;
    private final SearchService searchService;
    private final MlaCitationFormatter mlaCitationFormatter;
    private final CuratedCatalogService curatedCatalogService;

    public BookStorageService(BookRepository bookRepository,
                              BookCoverRepository bookCoverRepository,
                              ChapterRepository chapterRepository,
                              ChapterAnalysisRepository chapterAnalysisRepository,
                              ChapterRecapRepository chapterRecapRepository,
                              ChapterQuizRepository chapterQuizRepository,
                              IllustrationRepository illustrationRepository,
                              CharacterRepository characterRepository,
                              QuizAttemptRepository quizAttemptRepository,
                              QuizTrophyRepository quizTrophyRepository,
                              SearchService searchService,
                              MlaCitationFormatter mlaCitationFormatter,
                              CuratedCatalogService curatedCatalogService) {
        this.bookRepository = bookRepository;
        this.bookCoverRepository = bookCoverRepository;
        this.chapterRepository = chapterRepository;
        this.chapterAnalysisRepository = chapterAnalysisRepository;
        this.chapterRecapRepository = chapterRecapRepository;
        this.chapterQuizRepository = chapterQuizRepository;
        this.illustrationRepository = illustrationRepository;
        this.characterRepository = characterRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.quizTrophyRepository = quizTrophyRepository;
        this.searchService = searchService;
        this.mlaCitationFormatter = mlaCitationFormatter;
        this.curatedCatalogService = curatedCatalogService;
    }

    public List<Book> getAllBooks() {
        return bookRepository.findAll().stream()
            .map(this::toBookDto)
            .toList();
    }

    public Optional<Book> getBook(String bookId) {
        return bookRepository.findById(bookId)
            .map(this::toBookDto);
    }

    @Transactional
    public Optional<Book> updateBookFeatures(String bookId,
                                             Boolean ttsEnabled,
                                             Boolean illustrationEnabled,
                                             Boolean characterEnabled) {
        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            return Optional.empty();
        }

        BookEntity book = bookOpt.get();
        if (ttsEnabled != null) {
            book.setTtsEnabled(ttsEnabled);
        }
        if (illustrationEnabled != null) {
            book.setIllustrationEnabled(illustrationEnabled);
        }
        if (characterEnabled != null) {
            book.setCharacterEnabled(characterEnabled);
        }

        BookEntity saved = bookRepository.save(book);
        return Optional.of(toBookDto(saved));
    }

    public Optional<ChapterContent> getChapterContent(String bookId, String chapterId) {
        return chapterRepository.findById(chapterId)
            .filter(chapter -> chapter.getBook().getId().equals(bookId))
            .map(this::toChapterContentDto);
    }

    public Optional<ChapterContent> getChapterContentByIndex(String bookId, int chapterIndex) {
        return chapterRepository.findByBookIdAndChapterIndex(bookId, chapterIndex)
            .map(this::toChapterContentDto);
    }

    @Transactional
    public Book saveBook(BookEntity book) {
        BookEntity saved = bookRepository.save(book);
        indexBook(saved);
        return toBookDto(saved);
    }

    @Transactional
    public boolean deleteBook(String bookId) {
        if (!bookRepository.existsById(bookId)) {
            return false;
        }
        deleteBookDependents(bookId);
        bookRepository.deleteById(bookId);
        try {
            searchService.deleteByBookId(bookId);
        } catch (Exception e) {
            System.err.println("Failed to remove book from search index: " + e.getMessage());
        }
        return true;
    }

    @Transactional
    public int deleteAllBooks() {
        List<BookEntity> books = bookRepository.findAll();
        int count = books.size();
        for (BookEntity book : books) {
            deleteBookDependents(book.getId());
            try {
                searchService.deleteByBookId(book.getId());
            } catch (Exception e) {
                System.err.println("Failed to remove book from search index: " + e.getMessage());
            }
            bookRepository.deleteById(book.getId());
        }
        return count;
    }

    public boolean existsBySource(String source, String sourceId) {
        return bookRepository.existsBySourceAndSourceId(source, sourceId);
    }

    @Transactional(readOnly = true)
    public Optional<Book> findBySource(String source, String sourceId) {
        return bookRepository.findBySourceAndSourceId(source, sourceId)
            .map(this::toBookDto);
    }

    @Transactional(readOnly = true)
    public Optional<String> getMlaCitation(String bookId) {
        return getMlaCitation(bookId, null, null, null);
    }

    @Transactional(readOnly = true)
    public Optional<String> getMlaCitation(String bookId,
                                           String siteBaseUrl,
                                           String chapterId,
                                           Integer paragraphIndex) {
        return bookRepository.findById(bookId)
                .map(book -> mlaCitationFormatter.format(new MlaCitationFormatter.Metadata(
                        book.getAuthor(),
                        book.getTitle(),
                        null,
                        null,
                        toSourceTitle(book),
                        "ClassicChatReader",
                        toReaderUrl(siteBaseUrl, bookId, chapterId, paragraphIndex),
                        java.time.LocalDate.now()
                )));
    }

    // Index a book and its content in the search index
    private void indexBook(BookEntity book) {
        try {
            searchService.indexBook(book.getId(), book.getTitle(), book.getAuthor());

            for (ChapterEntity chapter : book.getChapters()) {
                for (ParagraphEntity paragraph : chapter.getParagraphs()) {
                    searchService.indexParagraph(
                        book.getId(),
                        chapter.getId(),
                        paragraph.getParagraphIndex(),
                        paragraph.getContent()
                    );
                }
            }
        } catch (Exception e) {
            // Log but don't fail the save operation
            System.err.println("Failed to index book: " + e.getMessage());
        }
    }

    // Reindex all books (useful after application restart)
    @Transactional(readOnly = true)
    public void reindexAll() {
        bookRepository.findAll().forEach(this::indexBook);
    }

    private void deleteBookDependents(String bookId) {
        quizAttemptRepository.deleteByBookId(bookId);
        chapterQuizRepository.deleteByBookId(bookId);
        chapterRecapRepository.deleteByBookId(bookId);
        chapterAnalysisRepository.deleteByBookId(bookId);
        illustrationRepository.deleteByBookId(bookId);
        bookCoverRepository.deleteByBookId(bookId);
        characterRepository.deleteByBookId(bookId);
        quizTrophyRepository.deleteByBookId(bookId);
    }

    // Convert entity to DTO
    private Book toBookDto(BookEntity entity) {
        List<Book.Chapter> chapters = entity.getChapters().stream()
            .map(ch -> new Book.Chapter(ch.getId(), ch.getTitle()))
            .toList();

        return new Book(
            entity.getId(),
            entity.getTitle(),
            entity.getAuthor(),
            entity.getDescription(),
            resolveCoverUrl(entity),
            chapters,
            Boolean.TRUE.equals(entity.getTtsEnabled()),
            Boolean.TRUE.equals(entity.getIllustrationEnabled()),
            Boolean.TRUE.equals(entity.getCharacterEnabled()),
            isCuratedBook(entity)
        );
    }

    private boolean isCuratedBook(BookEntity entity) {
        if (!"gutenberg".equalsIgnoreCase(entity.getSource()) || entity.getSourceId() == null) {
            return false;
        }
        try {
            return curatedCatalogService.isCuratedGutenbergId(Integer.parseInt(entity.getSourceId()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String resolveCoverUrl(BookEntity entity) {
        return bookCoverRepository.findByBookId(entity.getId())
                .filter(cover -> cover.getImageFilename() != null && !cover.getImageFilename().isBlank())
                .map(cover -> buildCoverUrl(entity.getId(), cover.getCompletedAt()))
                .orElse(entity.getCoverUrl());
    }

    private String buildCoverUrl(String bookId, java.time.LocalDateTime completedAt) {
        String baseUrl = "/api/library/" + bookId + "/cover";
        if (completedAt == null) {
            return baseUrl;
        }
        String version = completedAt.toString().replaceAll("[^0-9]", "");
        return version.isBlank() ? baseUrl : baseUrl + "?v=" + version;
    }

    private ChapterContent toChapterContentDto(ChapterEntity entity) {
        List<Paragraph> paragraphs = entity.getParagraphs().stream()
            .map(p -> new Paragraph(p.getParagraphIndex(), p.getContent()))
            .toList();

        return new ChapterContent(
            entity.getBook().getId(),
            entity.getId(),
            entity.getTitle(),
            paragraphs
        );
    }

    private String toReaderUrl(String siteBaseUrl, String bookId, String chapterId, Integer paragraphIndex) {
        if (siteBaseUrl == null || siteBaseUrl.isBlank()) {
            return "";
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(siteBaseUrl)
                .replaceQuery(null)
                .fragment(null)
                .queryParam("book", bookId);
        if (chapterId != null && !chapterId.isBlank()) {
            builder.queryParam("chapter", chapterId);
        }
        if (paragraphIndex != null && paragraphIndex >= 0) {
            builder.queryParam("paragraph", paragraphIndex + 1);
        }
        return builder.build().toUriString();
    }

    private String toSourceTitle(BookEntity book) {
        if (book == null || book.getSource() == null) {
            return "";
        }
        return switch (book.getSource().trim().toLowerCase()) {
            case "gutenberg" -> "Project Gutenberg";
            case "standardebooks" -> "Standard Ebooks";
            default -> "";
        };
    }
}
