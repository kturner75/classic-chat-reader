package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChatExportJobEntity;
import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChatExportJobRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.repository.TermRepository;
import com.classicchatreader.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherChatExportServiceTest {

    private final ClassroomAuthorizationService authorization = mock(ClassroomAuthorizationService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final TermRepository terms = mock(TermRepository.class);
    private final ReadingBuddyMessageRepository messages = mock(ReadingBuddyMessageRepository.class);
    private final BookRepository books = mock(BookRepository.class);
    private final ChatExportJobRepository jobs = mock(ChatExportJobRepository.class);
    private final EducationRecordAccessLogService accessLog = mock(EducationRecordAccessLogService.class);
    private final TeacherChatExportService service = new TeacherChatExportService(
            authorization, users, enrollments, terms, messages, books, jobs, accessLog);
    private final List<ReadingBuddyMessageEntity> stored = new ArrayList<>();

    private static ReadingBuddyMessageEntity message(String id, String role, String content, LocalDateTime at, long sequence) {
        ReadingBuddyMessageEntity m = new ReadingBuddyMessageEntity();
        m.setId(id);
        m.setOwnerKey("user:student-1");
        m.setBookId("book-1");
        m.setPersonaId("sage");
        m.setRole(role);
        m.setKind("chat");
        m.setContent(content);
        m.setChapterIndex(2);
        m.setCreatedAt(at);
        m.setChronologySequence(sequence);
        return m;
    }

    @BeforeEach
    void setup() {
        when(users.existsById("teacher-1")).thenReturn(true);
        when(authorization.canExportStudentChats("teacher-1", "term-1")).thenReturn(true);
        when(enrollments.findByTermIdAndUserIdAndDeletedAtIsNull("term-1", "student-1")).thenReturn(Optional.of(new EnrollmentEntity()));
        TermEntity term = new TermEntity();
        term.setId("term-1");
        term.setName("Fall 2026");
        term.setStartDate(LocalDate.of(2026, 8, 24));
        term.setEndDate(LocalDate.of(2026, 12, 12));
        when(terms.findByIdAndDeletedAtIsNull("term-1")).thenReturn(Optional.of(term));
        stored.add(message("late", "buddy", "Consider Darcy's pride.", LocalDateTime.of(2026, 9, 1, 10, 0), 2));
        stored.add(message("early", "user", "Why is Darcy rude?", LocalDateTime.of(2026, 9, 1, 10, 0), 1));
        stored.add(message("before-term", "user", "Summer question", LocalDateTime.of(2026, 8, 23, 23, 59), 3));
        stored.add(message("last-day", "user", "Final question", LocalDateTime.of(2026, 12, 12, 23, 59), 4));
        stored.add(message("after-term", "user", "Winter question", LocalDateTime.of(2026, 12, 13, 0, 0), 5));
        when(messages.findByOwnerKey("user:student-1")).thenReturn(stored);
        BookEntity book = new BookEntity("Pride and Prejudice", "Austen, Jane", "gutenberg");
        book.setId("book-1");
        when(books.findAllById(any())).thenReturn(List.of(book));
        when(jobs.save(any())).thenAnswer(invocation -> {
            ChatExportJobEntity job = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(job, "id", "export-1");
            return job;
        });
    }

    @Test
    void jsonExportContainsOnlyThisTermsReadingBuddyMessagesInOrderAndIsAudited() throws Exception {
        TeacherChatExportService.ExportFile file = service.exportReadingBuddy("teacher-1", "term-1", "student-1", "json", null);

        JsonNode doc = new ObjectMapper().readTree(file.bytes());
        assertEquals("export-1", doc.get("exportId").asText());
        assertEquals("READING_BUDDY", doc.get("source").asText());
        assertEquals("Fall 2026", doc.get("termName").asText());
        List<String> contents = new ArrayList<>();
        doc.get("messages").forEach(m -> contents.add(m.get("content").asText()));
        assertEquals(List.of("Why is Darcy rude?", "Consider Darcy's pride.", "Final question"), contents);
        assertEquals("Pride and Prejudice", doc.get("messages").get(0).get("bookTitle").asText());
        assertTrue(doc.get("note").asText().contains("Character chats are private"));
        assertEquals(3, file.messageCount());
        assertEquals("application/json", file.contentType());
        assertEquals("reading-buddy-term-1-student-1.json", file.filename());

        verify(accessLog).recordAccessWithinTransaction("teacher-1", "student-1", "term-1", EducationRecordAccessLogEntity.ACCESS_EXPORT_CHAT,
                EducationRecordAccessLogEntity.RESOURCE_CHAT_EXPORT_JOB, "export-1", null);
        verify(jobs).save(argThat(job -> "JSON".equals(job.getFormat()) && "READING_BUDDY".equals(job.getChatSources())
                && LocalDateTime.of(2026, 8, 24, 0, 0).equals(job.getFilterFrom())
                && LocalDateTime.of(2026, 12, 13, 0, 0).equals(job.getFilterTo())));
    }

    @Test
    void textExportIsReadable() {
        TeacherChatExportService.ExportFile file = service.exportReadingBuddy("teacher-1", "term-1", "student-1", "TXT", null);
        String text = new String(file.bytes(), StandardCharsets.UTF_8);
        assertTrue(text.contains("Pride and Prejudice · sage · user: Why is Darcy rude?"), text);
        assertFalse(text.contains("Winter question"));
        assertEquals("text/plain;charset=UTF-8", file.contentType());
    }

    @Test
    void auditFailureReturnsNothing() {
        doThrow(new IllegalStateException("audit unavailable")).when(accessLog)
                .recordAccessWithinTransaction(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> service.exportReadingBuddy("teacher-1", "term-1", "student-1", "json", null));
    }

    private HttpStatus status(Runnable call) {
        return HttpStatus.valueOf(assertThrows(ResponseStatusException.class, call::run).getStatusCode().value());
    }

    @Test
    void refusesBadFormatsNonTeachersUnknownStudentsAndSelfExportWithoutAnyWrite() {
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.exportReadingBuddy("teacher-1", "term-1", "student-1", "pdf", null)));
        assertEquals(HttpStatus.UNAUTHORIZED, status(() -> service.exportReadingBuddy("ghost", "term-1", "student-1", "json", null)));
        when(users.existsById("student-2")).thenReturn(true);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> service.exportReadingBuddy("student-2", "term-1", "student-1", "json", null)));
        assertEquals(HttpStatus.NOT_FOUND, status(() -> service.exportReadingBuddy("teacher-1", "term-1", "stranger", "json", null)));
        assertEquals(HttpStatus.NOT_FOUND, status(() -> service.exportReadingBuddy("teacher-1", "term-1", "teacher-1", "json", null)));
        verifyNoInteractions(jobs, accessLog, messages);
    }

    @Test
    void oversizedExportsAreRefusedBeforeAnyRecordIsWritten() {
        List<ReadingBuddyMessageEntity> many = new ArrayList<>();
        for (int i = 0; i <= TeacherChatExportService.MAX_MESSAGES; i++) {
            many.add(message("m" + i, "user", "x", LocalDateTime.of(2026, 9, 2, 0, 0), i));
        }
        when(messages.findByOwnerKey("user:student-1")).thenReturn(many);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, status(() -> service.exportReadingBuddy("teacher-1", "term-1", "student-1", "json", null)));
        verifyNoInteractions(jobs, accessLog);
    }

    @Test
    void openEndedTermsIncludeEverythingOnThatSide() {
        TermEntity open = new TermEntity();
        open.setId("term-1");
        open.setName("Open");
        when(terms.findByIdAndDeletedAtIsNull("term-1")).thenReturn(Optional.of(open));
        assertEquals(5, service.exportReadingBuddy("teacher-1", "term-1", "student-1", null, null).messageCount());
    }
}
