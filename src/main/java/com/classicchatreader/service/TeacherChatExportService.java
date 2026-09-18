package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChatExportJobEntity;
import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChatExportJobRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.repository.TermRepository;
import com.classicchatreader.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Teacher export of a student's server-side chats (BL-043.7 / BL-025.7).
 *
 * <p>v1 exports Reading Buddy messages only, per the data model's exportable inventory.
 * Character chats are excluded: My Chats promises students those sessions are private from
 * teachers. Exports are generated synchronously; the job row records the export and the
 * access log records the disclosure (fail-closed) before any bytes are returned.
 */
@Service
public class TeacherChatExportService {

    public static final int MAX_MESSAGES = 20_000;
    private static final Set<String> FORMATS = Set.of("JSON", "TXT");

    private final ClassroomAuthorizationService authorizationService;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final TermRepository termRepository;
    private final ReadingBuddyMessageRepository readingBuddyMessageRepository;
    private final BookRepository bookRepository;
    private final ChatExportJobRepository chatExportJobRepository;
    private final EducationRecordAccessLogService accessLogService;
    private final ObjectMapper json;

    public TeacherChatExportService(
            ClassroomAuthorizationService authorizationService,
            UserRepository userRepository,
            EnrollmentRepository enrollmentRepository,
            TermRepository termRepository,
            ReadingBuddyMessageRepository readingBuddyMessageRepository,
            BookRepository bookRepository,
            ChatExportJobRepository chatExportJobRepository,
            EducationRecordAccessLogService accessLogService) {
        this.authorizationService = authorizationService;
        this.userRepository = userRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.termRepository = termRepository;
        this.readingBuddyMessageRepository = readingBuddyMessageRepository;
        this.bookRepository = bookRepository;
        this.chatExportJobRepository = chatExportJobRepository;
        this.accessLogService = accessLogService;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public record ExportFile(String jobId, String filename, String contentType, byte[] bytes, int messageCount) {}

    @Transactional
    public ExportFile exportReadingBuddy(String teacherUserId, String termId, String studentUserId,
                                         String requestedFormat, HttpServletRequest request) {
        String format = requestedFormat == null ? "JSON" : requestedFormat.trim().toUpperCase(Locale.ROOT);
        if (!FORMATS.contains(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export format must be json or txt.");
        }
        if (teacherUserId == null || teacherUserId.isBlank() || !userRepository.existsById(teacherUserId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
        if (!authorizationService.canManageTerm(teacherUserId, termId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher access required.");
        }
        // "has or had" enrollment: dropped or completed students stay exportable for the term; deleted rows do not.
        if (studentUserId == null || studentUserId.equals(teacherUserId)
                || enrollmentRepository.findByTermIdAndUserIdAndDeletedAtIsNull(termId, studentUserId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found on roster.");
        }
        TermEntity term = termRepository.findByIdAndDeletedAtIsNull(termId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Term not found."));

        // Messages have no term_id; approximate the term with its calendar dates (inclusive days).
        LocalDateTime from = term.getStartDate() == null ? null : term.getStartDate().atStartOfDay();
        LocalDateTime to = term.getEndDate() == null ? null : term.getEndDate().plusDays(1).atStartOfDay();
        List<ReadingBuddyMessageEntity> messages = readingBuddyMessageRepository.findByOwnerKey("user:" + studentUserId).stream()
                .filter(m -> from == null || !m.getCreatedAt().isBefore(from))
                .filter(m -> to == null || m.getCreatedAt().isBefore(to))
                .sorted(Comparator.comparing(ReadingBuddyMessageEntity::getCreatedAt)
                        .thenComparingLong(ReadingBuddyMessageEntity::getChronologySequence)
                        .thenComparing(ReadingBuddyMessageEntity::getId))
                .toList();
        if (messages.size() > MAX_MESSAGES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "This export has more than " + MAX_MESSAGES + " messages. Contact support for a bulk export.");
        }

        ChatExportJobEntity job = chatExportJobRepository.save(new ChatExportJobEntity(teacherUserId, studentUserId, termId,
                format, ChatExportJobEntity.SOURCE_READING_BUDDY, from, to));
        // Fail-closed: no audit row, no export. The job row rolls back with this transaction if the audit write fails.
        accessLogService.recordAccess(teacherUserId, studentUserId, termId, EducationRecordAccessLogEntity.ACCESS_EXPORT_CHAT,
                EducationRecordAccessLogEntity.RESOURCE_CHAT_EXPORT_JOB, job.getId(), request);

        Map<String, String> titles = new HashMap<>();
        for (BookEntity book : bookRepository.findAllById(messages.stream().map(ReadingBuddyMessageEntity::getBookId).distinct().toList())) {
            titles.put(book.getId(), book.getTitle());
        }
        byte[] bytes = "TXT".equals(format) ? text(job, term, messages, titles) : jsonDocument(job, term, messages, titles);
        String extension = format.toLowerCase(Locale.ROOT);
        return new ExportFile(job.getId(), "reading-buddy-" + termId + "-" + studentUserId + "." + extension,
                "TXT".equals(format) ? "text/plain;charset=UTF-8" : "application/json", bytes, messages.size());
    }

    private byte[] jsonDocument(ChatExportJobEntity job, TermEntity term, List<ReadingBuddyMessageEntity> messages, Map<String, String> titles) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("exportId", job.getId());
        document.put("source", ChatExportJobEntity.SOURCE_READING_BUDDY);
        document.put("studentUserId", job.getSubjectUserId());
        document.put("termId", job.getTermId());
        document.put("termName", term.getName());
        document.put("windowFrom", job.getFilterFrom());
        document.put("windowToExclusive", job.getFilterTo());
        document.put("exportedAt", job.getCreatedAt());
        document.put("note", "Reading Buddy messages only. Character chats are private to the student and are not included.");
        document.put("messages", messages.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("createdAt", m.getCreatedAt());
            row.put("bookId", m.getBookId());
            row.put("bookTitle", titles.get(m.getBookId()));
            row.put("persona", m.getPersonaId());
            row.put("role", m.getRole());
            row.put("kind", m.getKind());
            row.put("chapterIndex", m.getChapterIndex());
            row.put("content", m.getContent());
            return row;
        }).toList());
        try {
            return json.writeValueAsBytes(document);
        } catch (Exception e) {
            throw new IllegalStateException("Chat export could not be serialized", e);
        }
    }

    private byte[] text(ChatExportJobEntity job, TermEntity term, List<ReadingBuddyMessageEntity> messages, Map<String, String> titles) {
        StringBuilder out = new StringBuilder()
                .append("Reading Buddy chat export\n")
                .append("Export: ").append(job.getId()).append('\n')
                .append("Student: ").append(job.getSubjectUserId()).append('\n')
                .append("Term: ").append(term.getName() == null ? job.getTermId() : term.getName()).append('\n')
                .append("Character chats are private to the student and are not included.\n\n");
        for (ReadingBuddyMessageEntity m : messages) {
            out.append('[').append(m.getCreatedAt()).append("] ")
                    .append(titles.getOrDefault(m.getBookId(), m.getBookId())).append(" · ")
                    .append(m.getPersonaId()).append(" · ").append(m.getRole()).append(": ")
                    .append(m.getContent()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
