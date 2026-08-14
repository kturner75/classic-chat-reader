package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentQuizEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ClassFeatureSettingsEntity;
import com.classicchatreader.repository.AssignmentQuizRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ClassFeatureSettingsRepository;
import com.classicchatreader.repository.ClassRoleMembershipRepository;
import com.classicchatreader.repository.ClassSectionRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.TermRepository;
import com.classicchatreader.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassroomAdminServiceAssignmentUpdateTest {

    @Mock private ClassSectionRepository classSectionRepository;
    @Mock private TermRepository termRepository;
    @Mock private ClassRoleMembershipRepository classRoleMembershipRepository;
    @Mock private ClassFeatureSettingsRepository classFeatureSettingsRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private AssignmentQuizRepository assignmentQuizRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private InviteLinkService inviteLinkService;
    @Mock private ClassroomAuthorizationService authorizationService;
    @Mock private BookRepository bookRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private UserRepository userRepository;
    @Mock private ClassroomProperties classroomProperties;
    @Mock private ClassroomTeacherCapabilityService teacherCapabilityService;
    @Mock private ClassroomEffectiveQuizService classroomEffectiveQuizService;
    @Mock private ChapterQuizService chapterQuizService;
    @Mock private jakarta.persistence.EntityManager entityManager;

    private ClassroomAdminService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomAdminService(
                classSectionRepository,
                termRepository,
                classRoleMembershipRepository,
                classFeatureSettingsRepository,
                assignmentRepository,
                assignmentQuizRepository,
                enrollmentRepository,
                inviteLinkService,
                authorizationService,
                bookRepository,
                chapterRepository,
                userRepository,
                classroomProperties,
                teacherCapabilityService,
                classroomEffectiveQuizService,
                chapterQuizService,
                entityManager
        );
    }

    @Test
    void createClassRequiresAccountCapabilityBeforeWritingClassroomData() {
        when(userRepository.existsById("student-1")).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Teaching access is not enabled for this account."))
                .when(teacherCapabilityService).requireCanCreateClass("student-1");

        ClassroomAdminService.CreateClassRequest request = new ClassroomAdminService.CreateClassRequest(
                "Literature 101", null, "Fall 2026", null, null, null
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createClass("student-1", request)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(classSectionRepository, termRepository, classRoleMembershipRepository);
    }

    @Test
    void updateFeatures_rejectsDisablingCharactersWhenAssignmentRequiresChat() {
        ClassFeatureSettingsEntity features = enabledFeatures();
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(classFeatureSettingsRepository.findById("term-1")).thenReturn(Optional.of(features));
        when(assignmentRepository.existsByTermIdAndCharacterChatRequiredTrueAndDeletedAtIsNull("term-1"))
                .thenReturn(true);

        ClassroomAdminService.FeatureUpdateRequest request = new ClassroomAdminService.FeatureUpdateRequest(
                null, null, null, null, false, null, null, null
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateFeatures("teacher-1", "term-1", request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("assignments require character chat"));
        assertTrue(features.isCharacterEnabled());
        verify(classFeatureSettingsRepository, never()).save(any(ClassFeatureSettingsEntity.class));
    }

    @Test
    void updateFeatures_rejectsDisablingChatWhenAssignmentRequiresChat() {
        ClassFeatureSettingsEntity features = enabledFeatures();
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(classFeatureSettingsRepository.findById("term-1")).thenReturn(Optional.of(features));
        when(assignmentRepository.existsByTermIdAndCharacterChatRequiredTrueAndDeletedAtIsNull("term-1"))
                .thenReturn(true);

        ClassroomAdminService.FeatureUpdateRequest request = new ClassroomAdminService.FeatureUpdateRequest(
                null, null, null, null, null, false, null, null
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateFeatures("teacher-1", "term-1", request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(features.isChatEnabled());
        verify(classFeatureSettingsRepository, never()).save(any(ClassFeatureSettingsEntity.class));
    }

    @Test
    void updateFeatures_allowsDisablingCharactersWhenNoAssignmentRequiresChat() {
        ClassFeatureSettingsEntity features = enabledFeatures();
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(classFeatureSettingsRepository.findById("term-1")).thenReturn(Optional.of(features));
        when(classFeatureSettingsRepository.save(features)).thenReturn(features);

        ClassroomAdminService.FeatureUpdateRequest request = new ClassroomAdminService.FeatureUpdateRequest(
                null, null, null, null, false, null, null, null
        );

        ClassFeatureSettingsEntity updated = service.updateFeatures("teacher-1", "term-1", request);

        assertFalse(updated.isCharacterEnabled());
        verify(classFeatureSettingsRepository).save(features);
    }

    @Test
    void updateAssignment_clearFlagsRemoveOptionalDates() {
        AssignmentEntity existing = baseAssignment();
        existing.setDueDate(LocalDate.of(2026, 7, 20));
        existing.setAvailableFromDate(LocalDate.of(2026, 7, 1));

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        true
                );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);

        assertNull(updated.getDueDate());
        assertNull(updated.getAvailableFromDate());
        assertEquals("Ch. 1 quiz", updated.getTitle());
    }

    @Test
    void updateAssignment_nullDatesWithoutClearFlagsLeaveExistingDates() {
        AssignmentEntity existing = baseAssignment();
        existing.setDueDate(LocalDate.of(2026, 7, 20));
        existing.setAvailableFromDate(LocalDate.of(2026, 7, 1));

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        "Renamed",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);

        assertEquals("Renamed", updated.getTitle());
        assertEquals(LocalDate.of(2026, 7, 20), updated.getDueDate());
        assertEquals(LocalDate.of(2026, 7, 1), updated.getAvailableFromDate());
    }

    @Test
    void updateAssignment_emptyChapterIdClearsChapterTarget() {
        AssignmentEntity existing = baseAssignment();
        existing.replaceChapters(List.of(chapterRow("ch-1", 0)));

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        null,
                        "book-1",
                        "",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);

        assertTrue(updated.isWholeBook());
        assertTrue(updated.getChapters().isEmpty());
    }

    @Test
    void updateAssignment_resetsAttemptWindowWhenQuizSourceChanges() {
        AssignmentEntity existing = baseAssignment();
        existing.setStatus("PUBLISHED");
        existing.setQuizRequired(true);
        existing.setQuizSource(AssignmentEntity.QUIZ_SOURCE_CUSTOM);
        existing.setQuizPassMinCorrect(1);
        existing.setQuizMaxRetries(2);
        existing.setQuizRulesActivatedAt(java.time.LocalDateTime.of(2026, 8, 1, 12, 0));
        existing.replaceChapters(List.of(chapterRow("ch-1", 0)));
        ChapterEntity chapter = chapterForBook("ch-1", "book-1");

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(chapterRepository.findByIdWithBook("ch-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapter));
        when(classroomEffectiveQuizService.resolveEffectiveQuestionCount("term-1", "ch-1"))
                .thenReturn(Optional.of(4));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        null,
                        "book-1",
                        null,
                        "ch-1",
                        0,
                        null,
                        null,
                        true,
                        false,
                        null,
                        "PUBLISHED",
                        null,
                        null,
                        1,
                        2,
                        null,
                        AssignmentEntity.QUIZ_SOURCE_CHAPTER
                );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);

        assertEquals(AssignmentEntity.QUIZ_SOURCE_CHAPTER, updated.getQuizSource());
        assertTrue(updated.getQuizRulesActivatedAt().isAfter(java.time.LocalDateTime.of(2026, 8, 1, 12, 0)));
    }

    @Test
    void updateAssignment_resolvesIndexOnlyChapterToJoinRow() {
        AssignmentEntity existing = baseAssignment();
        ChapterEntity chapterThree = chapterForBook("ch-3", "book-1", 2, "Chapter Three");

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(chapterRepository.findByBookIdAndChapterIndex("book-1", 2)).thenReturn(Optional.of(chapterThree));
        when(chapterRepository.findByIdWithBook("ch-3")).thenReturn(Optional.of(chapterThree));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapterThree));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        null,
                        "book-1",
                        null,
                        2,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);

        assertEquals(1, updated.getChapters().size());
        assertEquals("ch-3", updated.getChapters().get(0).getChapterId());
        assertEquals(2, updated.getChapters().get(0).getChapterIndex());
    }

    @Test
    void updateAssignment_reusesExistingChapterRowsWhenChapterIdsUnchanged() {
        AssignmentEntity existing = baseAssignment();
        AssignmentChapterEntity existingRow = chapterRow("ch-1", 0);
        existingRow.setId("ac-existing");
        existing.replaceChapters(List.of(existingRow));
        ChapterEntity chapter = chapterForBook("ch-1", "book-1", 0, "Chapter 1");

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(chapterRepository.findByIdWithBook("ch-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapter));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request = new ClassroomAdminService.AssignmentWriteRequest(
                "Ch. 1 quiz",
                "book-1",
                List.of("ch-1"),
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                "DRAFT",
                null,
                null,
                null,
                null,
                null,
                null
        );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);

        assertEquals(1, updated.getChapters().size());
        assertEquals("ac-existing", updated.getChapters().get(0).getId());
        assertEquals(existingRow, updated.getChapters().get(0));
    }

    @Test
    void updateAssignment_keepsOverlappingChapterAndAddsNew() {
        AssignmentEntity existing = baseAssignment();
        AssignmentChapterEntity keep = chapterRow("ch-1", 0);
        keep.setId("ac-keep");
        AssignmentChapterEntity drop = chapterRow("ch-2", 1);
        drop.setId("ac-drop");
        existing.replaceChapters(List.of(keep, drop));
        ChapterEntity ch1 = chapterForBook("ch-1", "book-1", 0, "One");
        ChapterEntity ch3 = chapterForBook("ch-3", "book-1", 2, "Three");

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(chapterRepository.findByIdWithBook("ch-1")).thenReturn(Optional.of(ch1));
        when(chapterRepository.findByIdWithBook("ch-3")).thenReturn(Optional.of(ch3));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(ch1, ch3));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request = new ClassroomAdminService.AssignmentWriteRequest(
                null,
                "book-1",
                List.of("ch-1", "ch-3"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);

        assertEquals(2, updated.getChapters().size());
        assertEquals("ac-keep", updated.getChapters().get(0).getId());
        assertEquals(keep, updated.getChapters().get(0));
        assertEquals("ch-3", updated.getChapters().get(1).getChapterId());
        assertEquals(1, updated.getChapters().get(1).getSortOrder());
    }

    @Test
    void deleteDraftAssignment_softDeletesDraft() {
        AssignmentEntity existing = baseAssignment();
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.deleteDraftAssignment("teacher-1", "assign-1");

        org.junit.jupiter.api.Assertions.assertNotNull(existing.getDeletedAt());
        verify(assignmentRepository).save(existing);
    }

    @Test
    void deleteDraftAssignment_rejectsPublished() {
        AssignmentEntity existing = baseAssignment();
        existing.setStatus("PUBLISHED");
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.deleteDraftAssignment("teacher-1", "assign-1")
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("Only draft assignments"));
        verify(assignmentRepository, never()).save(any(AssignmentEntity.class));
        assertNull(existing.getDeletedAt());
    }

    @Test
    void deleteDraftAssignment_hidesUnauthorizedAssignment() {
        AssignmentEntity existing = baseAssignment();
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.deleteDraftAssignment("teacher-1", "assign-1")
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(assignmentRepository, never()).save(any(AssignmentEntity.class));
    }

    private static AssignmentEntity baseAssignment() {
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setId("assign-1");
        assignment.setTermId("term-1");
        assignment.setTitle("Ch. 1 quiz");
        assignment.setBookId("book-1");
        assignment.setStatus("DRAFT");
        return assignment;
    }

    private static ClassFeatureSettingsEntity enabledFeatures() {
        ClassFeatureSettingsEntity features = new ClassFeatureSettingsEntity();
        features.setTermId("term-1");
        features.setCharacterEnabled(true);
        features.setChatEnabled(true);
        return features;
    }

    private static ChapterEntity chapterForBook(String chapterId, String bookId) {
        return chapterForBook(chapterId, bookId, 0, "Chapter 1");
    }

    private static ChapterEntity chapterForBook(String chapterId, String bookId, int index, String title) {
        BookEntity book = new BookEntity();
        book.setId(bookId);
        ChapterEntity chapter = new ChapterEntity(index, title);
        chapter.setId(chapterId);
        chapter.setBook(book);
        return chapter;
    }

    private static AssignmentChapterEntity chapterRow(String chapterId, int index) {
        AssignmentChapterEntity row = new AssignmentChapterEntity();
        row.setChapterId(chapterId);
        row.setChapterIndex(index);
        row.setSortOrder(index);
        return row;
    }

    @Test
    void createAssignment_savesDraftWithoutQuiz() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        "Read Chapter 1",
                        "book-1",
                        "",
                        null,
                        null,
                        null,
                        false,
                        false,
                        null,
                        "DRAFT"
                );

        AssignmentEntity saved = service.createAssignment("teacher-1", "term-1", request);

        assertEquals("Read Chapter 1", saved.getTitle());
        assertEquals("book-1", saved.getBookId());
        assertTrue(saved.isWholeBook());
        assertFalse(saved.isQuizRequired());
        assertEquals("DRAFT", saved.getStatus());
    }

    @Test
    void createAssignment_storesMultipleChaptersInBookOrder() {
        ChapterEntity ch1 = chapterForBook("chapter-1", "book-1", 0, "One");
        ChapterEntity ch2 = chapterForBook("chapter-2", "book-1", 1, "Two");
        ChapterEntity ch3 = chapterForBook("chapter-3", "book-1", 2, "Three");
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(chapterRepository.findByIdWithBook("chapter-3")).thenReturn(Optional.of(ch3));
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(ch1));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(ch1, ch2, ch3));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request = new ClassroomAdminService.AssignmentWriteRequest(
                "Chapters 1 and 3",
                "book-1",
                List.of("chapter-3", "chapter-1"),
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                "DRAFT",
                null,
                null,
                null,
                null,
                null,
                null
        );

        AssignmentEntity saved = service.createAssignment("teacher-1", "term-1", request);

        assertEquals(2, saved.getChapters().size());
        assertEquals("chapter-1", saved.getChapters().get(0).getChapterId());
        assertEquals("chapter-3", saved.getChapters().get(1).getChapterId());
        assertEquals(0, saved.getChapters().get(0).getSortOrder());
        assertEquals(1, saved.getChapters().get(1).getSortOrder());
    }

    @Test
    void createAssignment_allowsPassRulesBeforeChapterQuizExists() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        ChapterEntity chapter = chapterForBook("chapter-1", "book-1");
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapter));
        when(classroomEffectiveQuizService.resolveEffectiveQuestionCount("term-1", "chapter-1"))
                .thenReturn(Optional.empty());
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        "Ch. 1 quiz",
                        "book-1",
                        "chapter-1",
                        0,
                        null,
                        null,
                        true,
                        false,
                        null,
                        "DRAFT",
                        null,
                        null,
                        3,
                        1,
                        null
                );

        AssignmentEntity saved = service.createAssignment("teacher-1", "term-1", request);

        assertTrue(saved.isQuizRequired());
        assertEquals(3, saved.getQuizPassMinCorrect());
        assertEquals(1, saved.getQuizMaxRetries());
        assertEquals("chapter-1", saved.singleChapterId());
        assertEquals(AssignmentEntity.QUIZ_SOURCE_CHAPTER, saved.getQuizSource());
    }

    @Test
    void createAssignment_allowsPassRulesOnWholeBookDraft() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        "Whole book quiz",
                        "book-1",
                        "",
                        null,
                        null,
                        null,
                        true,
                        false,
                        null,
                        "DRAFT",
                        null,
                        null,
                        3,
                        1,
                        null
                );

        AssignmentEntity saved = service.createAssignment("teacher-1", "term-1", request);
        assertTrue(saved.isWholeBook());
        assertEquals(AssignmentEntity.QUIZ_SOURCE_CUSTOM, saved.getQuizSource());
        assertEquals(3, saved.getQuizPassMinCorrect());
    }

    @Test
    void createAssignment_rejectsPublishedQuizRequiredWithoutChapterQuiz() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        ChapterEntity chapter = chapterForBook("chapter-1", "book-1");
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of(chapter));
        when(classroomEffectiveQuizService.resolveEffectiveQuestionCount("term-1", "chapter-1"))
                .thenReturn(Optional.empty());
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        "Ch. 1 quiz",
                        "book-1",
                        "chapter-1",
                        0,
                        null,
                        null,
                        true,
                        false,
                        null,
                        "PUBLISHED"
                );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createAssignment("teacher-1", "term-1", request)
        );
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("Define an assignment quiz")
                || error.getReason().contains("No default chapter quiz"));
    }

    @Test
    void createAssignment_rejectsPassMinAboveEffectiveQuizSize() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        ChapterEntity chapter = chapterForBook("chapter-1", "book-1");
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(classroomEffectiveQuizService.resolveEffectiveQuestionCount("term-1", "chapter-1"))
                .thenReturn(Optional.of(4));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        "Ch. 1 quiz",
                        "book-1",
                        "chapter-1",
                        0,
                        null,
                        null,
                        true,
                        false,
                        null,
                        "DRAFT",
                        null,
                        null,
                        7,
                        1,
                        null
                );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createAssignment("teacher-1", "term-1", request)
        );
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("cannot exceed the effective quiz size"));
        verify(assignmentRepository, never()).save(any(AssignmentEntity.class));
    }

    @Test
    void updateAssignment_setsCharacterChatRequiredWhenFeaturesAllow() {
        AssignmentEntity existing = baseAssignment();
        ClassFeatureSettingsEntity features = new ClassFeatureSettingsEntity();
        features.setTermId("term-1");
        features.setCharacterEnabled(true);
        features.setChatEnabled(true);

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(classFeatureSettingsRepository.findById("term-1")).thenReturn(Optional.of(features));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        null, null, null, null, null, null,
                        null, true, null, null
                );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);
        assertTrue(updated.isCharacterChatRequired());
    }

    @Test
    void updateAssignment_rejectsCharacterChatRequiredWhenFeaturesDisabled() {
        AssignmentEntity existing = baseAssignment();
        ClassFeatureSettingsEntity features = new ClassFeatureSettingsEntity();
        features.setTermId("term-1");
        features.setCharacterEnabled(false);
        features.setChatEnabled(true);

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(classFeatureSettingsRepository.findById("term-1")).thenReturn(Optional.of(features));

        ClassroomAdminService.AssignmentWriteRequest request =
                new ClassroomAdminService.AssignmentWriteRequest(
                        null, null, null, null, null, null,
                        null, true, null, null
                );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateAssignment("teacher-1", "assign-1", request)
        );
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void updateAssignment_acceptsSmallerCustomQuizWhenQuestionsTravelWithPassMin() {
        AssignmentEntity existing = baseAssignment();
        existing.setStatus("PUBLISHED");
        existing.setQuizRequired(true);
        existing.setQuizSource(AssignmentEntity.QUIZ_SOURCE_CUSTOM);
        existing.setQuizPassMinCorrect(8);
        existing.setQuizMaxRetries(1);
        AssignmentQuizEntity currentQuiz = new AssignmentQuizEntity();
        currentQuiz.setAssignmentId("assign-1");
        currentQuiz.setPayloadJson("{\"questions\":[{},{},{},{},{},{},{},{},{},{}]}");

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(assignmentQuizRepository.findByAssignmentId("assign-1")).thenReturn(Optional.of(currentQuiz));
        when(assignmentQuizRepository.save(any(AssignmentQuizEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentQuizRepository.existsByAssignmentId("assign-1")).thenReturn(true);
        when(chapterQuizService.parsePayloadJson(any())).thenReturn(new com.classicchatreader.model.ChapterQuizPayload(List.of()));
        when(chapterQuizService.contentVersion(any())).thenReturn("old-version", "new-version");
        when(chapterQuizService.serializePayload(any())).thenReturn("{\"questions\":[{},{},{},{},{}]}");
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<com.classicchatreader.model.ChapterQuizPayload.Question> questions = List.of(
                new com.classicchatreader.model.ChapterQuizPayload.Question("q1", "One?", List.of("A", "B", "C", "D"), 0, null, null),
                new com.classicchatreader.model.ChapterQuizPayload.Question("q2", "Two?", List.of("A", "B", "C", "D"), 1, null, null),
                new com.classicchatreader.model.ChapterQuizPayload.Question("q3", "Three?", List.of("A", "B", "C", "D"), 2, null, null),
                new com.classicchatreader.model.ChapterQuizPayload.Question("q4", "Four?", List.of("A", "B", "C", "D"), 3, null, null),
                new com.classicchatreader.model.ChapterQuizPayload.Question("q5", "Five?", List.of("A", "B", "C", "D"), 0, null, null)
        );
        ClassroomAdminService.AssignmentWriteRequest request = new ClassroomAdminService.AssignmentWriteRequest(
                null, null, null, null, null, null, null,
                true, false, null, "PUBLISHED",
                null, null, 4, 1, null,
                AssignmentEntity.QUIZ_SOURCE_CUSTOM,
                questions
        );

        AssignmentEntity updated = service.updateAssignment("teacher-1", "assign-1", request);
        assertEquals(4, updated.getQuizPassMinCorrect());
        assertEquals(AssignmentEntity.QUIZ_SOURCE_CUSTOM, updated.getQuizSource());
        verify(assignmentQuizRepository).save(any(AssignmentQuizEntity.class));
    }
}
