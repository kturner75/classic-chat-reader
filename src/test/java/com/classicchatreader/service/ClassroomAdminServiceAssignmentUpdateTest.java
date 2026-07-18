package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.ClassFeatureSettingsEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
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
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private InviteLinkService inviteLinkService;
    @Mock private ClassroomAuthorizationService authorizationService;
    @Mock private BookRepository bookRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private UserRepository userRepository;
    @Mock private ClassroomProperties classroomProperties;
    @Mock private ClassroomTeacherCapabilityService teacherCapabilityService;

    private ClassroomAdminService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomAdminService(
                classSectionRepository,
                termRepository,
                classRoleMembershipRepository,
                classFeatureSettingsRepository,
                assignmentRepository,
                enrollmentRepository,
                inviteLinkService,
                authorizationService,
                bookRepository,
                chapterRepository,
                userRepository,
                classroomProperties,
                teacherCapabilityService
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
        existing.setChapterId("ch-1");
        existing.setChapterIndex(0);

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

        assertNull(updated.getChapterId());
        assertNull(updated.getChapterIndex());
    }

    @Test
    void updateAssignment_preservesChapterIndexWhenOnlyIndexProvided() {
        AssignmentEntity existing = baseAssignment();
        existing.setChapterId(null);
        existing.setChapterIndex(2);

        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("assign-1")).thenReturn(Optional.of(existing));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(chapterRepository.findByBookIdAndChapterIndex("book-1", 2))
                .thenReturn(Optional.of(new com.classicchatreader.entity.ChapterEntity(2, "Chapter Three")));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Teacher re-saves index-only chapter target (no chapterId).
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

        assertNull(updated.getChapterId());
        assertEquals(2, updated.getChapterIndex());

        ArgumentCaptor<AssignmentEntity> captor = ArgumentCaptor.forClass(AssignmentEntity.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals(2, captor.getValue().getChapterIndex());
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
}
