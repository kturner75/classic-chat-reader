package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.InviteLinkEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassroomAdminServiceInviteTest {

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
    @Mock private ClassroomTeacherCapabilityService teacherCapabilityService;
    @Mock private ClassroomEffectiveQuizService classroomEffectiveQuizService;
    @Mock private ChapterQuizService chapterQuizService;
    @Mock private jakarta.persistence.EntityManager entityManager;

    private ClassroomProperties classroomProperties;
    private ClassroomAdminService service;

    @BeforeEach
    void setUp() {
        classroomProperties = new ClassroomProperties();
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
    void createInviteAppliesDefaultTtlAndMaxUsesAndRotates() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(30);
        when(inviteLinkService.issueReplacingActive(
                eq("term-1"), eq("teacher-1"), eq("Teacher workspace invite"), eq(40), any(LocalDateTime.class)))
                .thenReturn(new InviteLinkService.IssuedInvite(
                        "link-2", "fresh-code", "code", "term-1", 40, expiresAt));

        InviteLinkService.IssuedInvite issued =
                service.createInvite("teacher-1", "term-1", "Teacher workspace invite");

        assertEquals("link-2", issued.inviteLinkId());
        assertEquals(40, issued.maxUses());
        assertNotNull(issued.expiresAt());
        ArgumentCaptor<LocalDateTime> expiryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(inviteLinkService).issueReplacingActive(
                eq("term-1"),
                eq("teacher-1"),
                eq("Teacher workspace invite"),
                eq(40),
                expiryCaptor.capture());
        LocalDateTime captured = expiryCaptor.getValue();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertTrue(!captured.isBefore(now.plusDays(29)) && !captured.isAfter(now.plusDays(31)));
        verify(inviteLinkService, never()).issue(any(), any(), any(), isNull(), isNull());
    }

    @Test
    void revokeInviteRequiresTeacherOfTerm() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-1");
        when(inviteLinkService.findById("link-1")).thenReturn(Optional.of(link));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);

        service.revokeInvite("teacher-1", "link-1");

        verify(inviteLinkService).revoke(link);
    }

    @Test
    void revokeInviteHidesUnknownOrForeignInvites() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(inviteLinkService.findById("missing")).thenReturn(Optional.empty());

        ResponseStatusException missing = assertThrows(
                ResponseStatusException.class,
                () -> service.revokeInvite("teacher-1", "missing"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setTermId("term-other");
        when(inviteLinkService.findById("link-1")).thenReturn(Optional.of(link));
        when(authorizationService.canManageTerm("teacher-1", "term-other")).thenReturn(false);

        ResponseStatusException forbidden = assertThrows(
                ResponseStatusException.class,
                () -> service.revokeInvite("teacher-1", "link-1"));
        assertEquals(HttpStatus.NOT_FOUND, forbidden.getStatusCode());
        verify(inviteLinkService, never()).revoke(any());
    }

    @Test
    void listInvitesReturnsActiveMetadata() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        InviteLinkEntity link = new InviteLinkEntity();
        link.setId("link-1");
        link.setCodeHint("abcd");
        link.setLabel("Default invite");
        link.setMaxUses(40);
        link.setUseCount(3);
        link.setExpiresAt(LocalDateTime.of(2026, 9, 22, 12, 0));
        when(inviteLinkService.listActive("term-1")).thenReturn(List.of(link));

        List<ClassroomAdminService.InviteSummary> rows = service.listInvites("teacher-1", "term-1");

        assertEquals(1, rows.size());
        assertEquals("link-1", rows.get(0).inviteLinkId());
        assertEquals("abcd", rows.get(0).codeHint());
        assertEquals(40, rows.get(0).maxUses());
        assertEquals(3, rows.get(0).useCount());
    }
}
