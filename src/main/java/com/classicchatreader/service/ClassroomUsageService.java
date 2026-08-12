package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentProgressEntity;
import com.classicchatreader.entity.ClassroomUsageEventEntity;
import com.classicchatreader.entity.TermEntity;
import com.classicchatreader.repository.AssignmentProgressRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.ClassroomUsageEventRepository;
import com.classicchatreader.repository.TermRepository;
import com.classicchatreader.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin BL-025.6 slice: assignment opened + reading heartbeats for BL-025.10 demos.
 * Not a full usage/event platform.
 */
@Service
public class ClassroomUsageService {

    static final long MAX_HEARTBEAT_DURATION_MS = 120_000L;
    static final long MIN_HEARTBEAT_DURATION_MS = 1L;

    private final ClassroomAuthorizationService authorizationService;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentProgressRepository assignmentProgressRepository;
    private final ClassroomUsageEventRepository usageEventRepository;
    private final TermRepository termRepository;
    private final UserRepository userRepository;

    public ClassroomUsageService(
            ClassroomAuthorizationService authorizationService,
            AssignmentRepository assignmentRepository,
            AssignmentProgressRepository assignmentProgressRepository,
            ClassroomUsageEventRepository usageEventRepository,
            TermRepository termRepository,
            UserRepository userRepository) {
        this.authorizationService = authorizationService;
        this.assignmentRepository = assignmentRepository;
        this.assignmentProgressRepository = assignmentProgressRepository;
        this.usageEventRepository = usageEventRepository;
        this.termRepository = termRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public OpenedResult markAssignmentOpened(String userId, String assignmentId) {
        requireUser(userId);
        AssignmentEntity assignment = assignmentRepository.findByIdAndDeletedAtIsNull(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found."));
        if (!"PUBLISHED".equals(assignment.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }
        if (!authorizationService.isActiveStudentOnTerm(userId, assignment.getTermId())) {
            // Avoid probing assignment IDs across classes.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }

        Optional<AssignmentProgressEntity> existing =
                assignmentProgressRepository.findByAssignmentIdAndUserId(assignmentId, userId);
        if (existing.isPresent()) {
            return new OpenedResult(
                    assignmentId,
                    true,
                    existing.get().getFirstOpenedAt().atOffset(ZoneOffset.UTC).toString(),
                    false);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AssignmentProgressEntity progress = new AssignmentProgressEntity();
        progress.setTermId(assignment.getTermId());
        progress.setAssignmentId(assignmentId);
        progress.setUserId(userId);
        progress.setFirstOpenedAt(now);
        try {
            assignmentProgressRepository.saveAndFlush(progress);
        } catch (DataIntegrityViolationException race) {
            AssignmentProgressEntity raced = assignmentProgressRepository
                    .findByAssignmentIdAndUserId(assignmentId, userId)
                    .orElseThrow(() -> race);
            return new OpenedResult(
                    assignmentId,
                    true,
                    raced.getFirstOpenedAt().atOffset(ZoneOffset.UTC).toString(),
                    false);
        }

        writeAssignmentViewEvent(userId, assignment, now);
        return new OpenedResult(assignmentId, true, now.atOffset(ZoneOffset.UTC).toString(), true);
    }

    @Transactional
    public HeartbeatResult recordReadingHeartbeat(String userId, HeartbeatRequest request) {
        requireUser(userId);
        if (request == null || isBlank(request.termId()) || isBlank(request.bookId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "termId and bookId are required.");
        }
        if (!authorizationService.isActiveStudentOnTerm(userId, request.termId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student enrollment required.");
        }

        long durationMs = clampDuration(request.durationMs());
        if (durationMs <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationMs must be positive.");
        }

        TermEntity term = termRepository.findByIdAndDeletedAtIsNull(request.termId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Term not found."));

        String idempotencyKey = trimToNull(request.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<ClassroomUsageEventEntity> existing = usageEventRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                ClassroomUsageEventEntity prior = existing.get();
                return new HeartbeatResult(prior.getId(), prior.getDurationMs() == null ? 0L : prior.getDurationMs(), true);
            }
        }

        ClassroomUsageEventEntity event = new ClassroomUsageEventEntity();
        event.setUserId(userId);
        event.setTermId(term.getId());
        event.setClassSectionId(term.getClassSectionId());
        event.setEventType(ClassroomUsageEventEntity.TYPE_READING_HEARTBEAT);
        event.setBookId(request.bookId().trim());
        event.setChapterId(trimToNull(request.chapterId()));
        event.setAssignmentId(trimToNull(request.assignmentId()));
        event.setDurationMs(durationMs);
        event.setSessionId(trimToNull(request.sessionId()));
        event.setIdempotencyKey(idempotencyKey);
        event.setFeature("reader");
        event.setOccurredAt(LocalDateTime.now(ZoneOffset.UTC));

        try {
            ClassroomUsageEventEntity saved = usageEventRepository.saveAndFlush(event);
            return new HeartbeatResult(saved.getId(), durationMs, false);
        } catch (DataIntegrityViolationException race) {
            if (idempotencyKey == null) {
                throw race;
            }
            ClassroomUsageEventEntity prior = usageEventRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race);
            return new HeartbeatResult(prior.getId(), prior.getDurationMs() == null ? 0L : prior.getDurationMs(), true);
        }
    }

    @Transactional(readOnly = true)
    public long sumApproximateReaderMs(String termId, String userId) {
        return usageEventRepository.sumDurationMsByTermUserAndType(
                termId, userId, ClassroomUsageEventEntity.TYPE_READING_HEARTBEAT);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> sumApproximateReaderMsByBook(String termId, String userId) {
        List<Object[]> rows = usageEventRepository.sumDurationMsByBook(
                termId, userId, ClassroomUsageEventEntity.TYPE_READING_HEARTBEAT);
        Map<String, Long> byBook = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            long ms = row[1] instanceof Number number ? number.longValue() : 0L;
            byBook.put(String.valueOf(row[0]), ms);
        }
        return byBook;
    }

    private void writeAssignmentViewEvent(String userId, AssignmentEntity assignment, LocalDateTime occurredAt) {
        TermEntity term = termRepository.findByIdAndDeletedAtIsNull(assignment.getTermId()).orElse(null);
        ClassroomUsageEventEntity event = new ClassroomUsageEventEntity();
        event.setUserId(userId);
        event.setTermId(assignment.getTermId());
        if (term != null) {
            event.setClassSectionId(term.getClassSectionId());
        }
        event.setEventType(ClassroomUsageEventEntity.TYPE_ASSIGNMENT_VIEW);
        event.setAssignmentId(assignment.getId());
        event.setBookId(assignment.getBookId());
        event.setChapterId(assignment.getChapterId());
        event.setDurationMs(0L);
        event.setFeature("assignment");
        event.setOccurredAt(occurredAt);
        event.setIdempotencyKey("assignment-view:" + assignment.getId() + ":" + userId);
        try {
            usageEventRepository.save(event);
        } catch (DataIntegrityViolationException ignored) {
            // Idempotent companion event for first open.
        }
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
    }

    static long clampDuration(Long durationMs) {
        if (durationMs == null) {
            return 0L;
        }
        if (durationMs < MIN_HEARTBEAT_DURATION_MS) {
            return 0L;
        }
        return Math.min(durationMs, MAX_HEARTBEAT_DURATION_MS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record HeartbeatRequest(
            String termId,
            String bookId,
            String chapterId,
            String assignmentId,
            Long durationMs,
            String sessionId,
            String idempotencyKey
    ) {
    }

    public record HeartbeatResult(String eventId, long acceptedDurationMs, boolean duplicate) {
    }

    public record OpenedResult(String assignmentId, boolean opened, String firstOpenedAt, boolean newlyOpened) {
    }
}
