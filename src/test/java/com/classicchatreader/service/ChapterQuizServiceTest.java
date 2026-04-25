package com.classicchatreader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ChapterQuizEntity;
import com.classicchatreader.entity.ChapterQuizStatus;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.model.ChapterQuizResponse;
import com.classicchatreader.repository.ChapterQuizRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterQuizServiceTest {

    @Mock
    private ChapterQuizRepository chapterQuizRepository;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private ParagraphRepository paragraphRepository;

    @Mock
    private LlmProvider reasoningProvider;

    @Mock
    private QuizProgressService quizProgressService;

    @Mock
    private QuizMetricsService quizMetricsService;

    private ChapterQuizService chapterQuizService;

    @BeforeEach
    void setUp() {
        chapterQuizService = new ChapterQuizService(
                chapterQuizRepository,
                chapterRepository,
                paragraphRepository,
                reasoningProvider,
                quizProgressService,
                quizMetricsService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(chapterQuizService, "maxContextChars", 7000);
        ReflectionTestUtils.setField(chapterQuizService, "questionsPerChapter", 3);
        ReflectionTestUtils.setField(chapterQuizService, "maxQuestions", 5);
        ReflectionTestUtils.setField(chapterQuizService, "difficultyRampEnabled", true);
        ReflectionTestUtils.setField(chapterQuizService, "difficultyRampChapterStep", 5);
        ReflectionTestUtils.setField(chapterQuizService, "difficultyRampMaxLevel", 3);
        ReflectionTestUtils.setField(chapterQuizService, "questionBoostPerDifficultyLevel", 1);
    }

    @Test
    void getChapterQuiz_withoutStoredQuiz_returnsMissingPayload() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterQuizRepository.findByChapterIdWithChapterAndBook("chapter-1")).thenReturn(Optional.empty());
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));

        Optional<ChapterQuizResponse> result = chapterQuizService.getChapterQuiz("chapter-1");

        assertTrue(result.isPresent());
        ChapterQuizResponse quiz = result.get();
        assertEquals("book-1", quiz.bookId());
        assertEquals("chapter-1", quiz.chapterId());
        assertEquals("MISSING", quiz.status());
        assertFalse(quiz.ready());
        assertEquals(List.of(), quiz.payload().questions());
    }

    @Test
    void getChapterQuiz_includesDifficultyLevelFromChapterIndex() throws Exception {
        ChapterEntity chapter = createChapter("book-1", "chapter-12", 12, "Chapter 12");
        ChapterQuizEntity entity = new ChapterQuizEntity(chapter);
        entity.setStatus(ChapterQuizStatus.COMPLETED);
        entity.setGeneratedAt(LocalDateTime.of(2026, 2, 11, 10, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 2, 11, 10, 1));
        entity.setPromptVersion("v1");
        entity.setModelName("grok");
        entity.setPayloadJson(new ObjectMapper().writeValueAsString(new ChapterQuizPayload(List.of(
                new ChapterQuizPayload.Question(
                        "Q1",
                        List.of("A", "B", "C", "D"),
                        0,
                        0,
                        "Citation"
                )
        ))));
        when(chapterQuizRepository.findByChapterIdWithChapterAndBook("chapter-12")).thenReturn(Optional.of(entity));

        var result = chapterQuizService.getChapterQuiz("chapter-12");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().difficultyLevel());
    }

    @Test
    void saveGeneratedQuiz_persistsCompletedRecord() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterQuizRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenReturn(List.of(new ParagraphEntity(0, "Alice finds the hidden key in the drawer.")));
        when(chapterQuizRepository.save(any(ChapterQuizEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        chapterQuizService.saveGeneratedQuiz(
                "chapter-1",
                new ChapterQuizPayload(List.of(
                        new ChapterQuizPayload.Question(
                                "Where does Alice find the key?",
                                List.of("In the drawer", "In the garden", "Under the table", "In the library"),
                                0,
                                0,
                                "Alice finds the hidden key in the drawer."
                        )
                )),
                "v1",
                "grok"
        );

        ArgumentCaptor<ChapterQuizEntity> captor = ArgumentCaptor.forClass(ChapterQuizEntity.class);
        verify(chapterQuizRepository).save(captor.capture());
        ChapterQuizEntity saved = captor.getValue();
        assertEquals(ChapterQuizStatus.COMPLETED, saved.getStatus());
        assertNotNull(saved.getGeneratedAt());
        assertEquals("v1", saved.getPromptVersion());
        assertEquals("grok", saved.getModelName());
        assertNotNull(saved.getPayloadJson());
        assertTrue(saved.getPayloadJson().contains("Where does Alice find the key?"));
    }

    @Test
    void saveGeneratedQuiz_missingChapter_throws() {
        when(chapterRepository.findById("missing")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> chapterQuizService.saveGeneratedQuiz("missing", null, "v1", "grok")
        );
        assertTrue(ex.getMessage().contains("Chapter not found"));
    }

    @Test
    void requestChapterQuiz_existingFailedQuiz_requeuesAsPending() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterQuizEntity quiz = new ChapterQuizEntity(chapter);
        quiz.setStatus(ChapterQuizStatus.FAILED);

        when(chapterQuizRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(quiz));

        chapterQuizService.requestChapterQuiz("chapter-1");

        ArgumentCaptor<ChapterQuizEntity> captor = ArgumentCaptor.forClass(ChapterQuizEntity.class);
        verify(chapterQuizRepository).save(captor.capture());
        assertEquals(ChapterQuizStatus.PENDING, captor.getValue().getStatus());
        verify(quizMetricsService).recordGenerationRequested();
    }

    @Test
    void processChapterQuiz_withAvailableProvider_persistsLlmGeneratedPayload() {
        ReflectionTestUtils.setField(chapterQuizService, "maxContextChars", 4000);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.getProviderName()).thenReturn("xai");
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                {
                  "questions": [
                    {
                      "question": "What does Holmes examine?",
                      "options": ["A clue", "A coin", "A map", "A letter"],
                      "correctOptionIndex": 0,
                      "citationParagraphIndex": 0,
                      "citationSnippet": "Holmes studies the clue."
                    }
                  ]
                }
                """);

        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterQuizRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());
        when(chapterQuizRepository.save(any(ChapterQuizEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenReturn(List.of(new ParagraphEntity(0, "Holmes studies the clue and explains his reasoning.")));

        ReflectionTestUtils.invokeMethod(chapterQuizService, "processChapterQuiz", "chapter-1");

        ArgumentCaptor<ChapterQuizEntity> captor = ArgumentCaptor.forClass(ChapterQuizEntity.class);
        verify(chapterQuizRepository).save(captor.capture());
        ChapterQuizEntity saved = captor.getValue();
        assertEquals(ChapterQuizStatus.COMPLETED, saved.getStatus());
        assertEquals("v1-llm-json", saved.getPromptVersion());
        assertEquals("xai", saved.getModelName());
        assertNotNull(saved.getPayloadJson());
        assertTrue(saved.getPayloadJson().contains("What does Holmes examine?"));
        verify(quizMetricsService).recordGenerationCompleted(eq(false), anyLong());
    }

    @Test
    void processChapterQuiz_whenSaveFails_recordsFailedGenerationMetric() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.empty());
        when(chapterQuizRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenReturn(List.of(new ParagraphEntity(0, "Holmes studies the clue and explains his reasoning.")));

        ReflectionTestUtils.invokeMethod(chapterQuizService, "processChapterQuiz", "chapter-1");

        verify(quizMetricsService).recordGenerationFailed(anyLong());
    }

    @Test
    void processChapterQuiz_cacheOnlyMode_skipsQueuedGeneration() {
        ReflectionTestUtils.setField(chapterQuizService, "cacheOnly", true);

        ReflectionTestUtils.invokeMethod(chapterQuizService, "processChapterQuiz", "chapter-1");

        verify(reasoningProvider, never()).generate(any(), any());
        verify(chapterRepository, never()).findByIdWithBook("chapter-1");
        verify(chapterQuizRepository, never()).save(any(ChapterQuizEntity.class));
    }

    @Test
    void gradeQuiz_completedQuiz_returnsScoreAndCitations() throws Exception {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterQuizEntity entity = new ChapterQuizEntity(chapter);
        entity.setStatus(ChapterQuizStatus.COMPLETED);
        entity.setGeneratedAt(LocalDateTime.of(2026, 2, 11, 10, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 2, 11, 10, 1));
        entity.setPayloadJson(new ObjectMapper().writeValueAsString(new ChapterQuizPayload(List.of(
                new ChapterQuizPayload.Question(
                        "What does Holmes examine?",
                        List.of("A clue", "A coin", "A map", "A letter"),
                        0,
                        0,
                        "Holmes studies the clue and explains his reasoning."
                ),
                new ChapterQuizPayload.Question(
                        "Who writes the report?",
                        List.of("Watson", "Holmes", "Lestrade", "Moriarty"),
                        0,
                        1,
                        "Watson documents the encounter in his report."
                )
        ))));

        when(chapterQuizRepository.findByChapterIdWithChapterAndBook("chapter-1")).thenReturn(Optional.of(entity));
        when(quizProgressService.recordAttemptAndEvaluate(
                any(),
                isNull(),
                isNull(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt()))
                .thenReturn(new QuizProgressService.ProgressUpdate(
                        List.of(),
                        new com.classicchatreader.model.QuizProgress(2, 1, 0)
                ));

        var graded = chapterQuizService.gradeQuiz("chapter-1", List.of(0, 2));

        assertTrue(graded.isPresent());
        assertEquals(2, graded.get().totalQuestions());
        assertEquals(1, graded.get().correctAnswers());
        assertEquals(50, graded.get().scorePercent());
        assertEquals(0, graded.get().difficultyLevel());
        assertFalse(graded.get().results().get(1).correct());
        assertEquals("Watson", graded.get().results().get(1).correctAnswer());
        assertTrue(graded.get().results().get(1).citationSnippet().contains("Watson"));
    }

    private ChapterEntity createChapter(String bookId, String chapterId, int index, String title) {
        BookEntity book = new BookEntity("Title", "Author", "gutenberg");
        book.setId(bookId);

        ChapterEntity chapter = new ChapterEntity(index, title);
        chapter.setId(chapterId);
        chapter.setBook(book);
        return chapter;
    }
}
