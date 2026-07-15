package com.classicchatreader.controller;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.ClassFeatureSettingsEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.ClassroomAdminService;
import com.classicchatreader.service.ClassroomAdminService.AssignmentWriteRequest;
import com.classicchatreader.service.ClassroomAdminService.ClassSummary;
import com.classicchatreader.service.ClassroomAdminService.CreateClassRequest;
import com.classicchatreader.service.ClassroomAdminService.CreateClassResult;
import com.classicchatreader.service.ClassroomAdminService.EnrollmentRow;
import com.classicchatreader.service.ClassroomAdminService.FeatureUpdateRequest;
import com.classicchatreader.service.ClassroomContextService;
import com.classicchatreader.service.ClassroomTeacherCapabilityService;
import com.classicchatreader.service.ClassroomTeacherCapabilityService.TeacherCapabilities;
import com.classicchatreader.service.InviteLinkService;
import com.classicchatreader.service.InviteLinkService.RedeemResult;
import com.classicchatreader.service.InviteLinkService.RedeemStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/classroom")
public class ClassroomController {

    private final ClassroomContextService classroomContextService;
    private final ClassroomAdminService classroomAdminService;
    private final InviteLinkService inviteLinkService;
    private final AccountAuthService accountAuthService;
    private final ClassroomTeacherCapabilityService teacherCapabilityService;

    public ClassroomController(
            ClassroomContextService classroomContextService,
            ClassroomAdminService classroomAdminService,
            InviteLinkService inviteLinkService,
            AccountAuthService accountAuthService,
            ClassroomTeacherCapabilityService teacherCapabilityService) {
        this.classroomContextService = classroomContextService;
        this.classroomAdminService = classroomAdminService;
        this.inviteLinkService = inviteLinkService;
        this.accountAuthService = accountAuthService;
        this.teacherCapabilityService = teacherCapabilityService;
    }

    @GetMapping("/context")
    public ClassroomContextResponse getContext(
            @RequestParam(name = "termId", required = false) String termId,
            HttpServletRequest request) {
        String userId = resolveUserId(request);
        return classroomContextService.getContext(userId, termId);
    }

    @GetMapping("/capabilities")
    public TeacherCapabilities getCapabilities(HttpServletRequest request) {
        return teacherCapabilityService.getCapabilities(resolveUserId(request));
    }

    @PostMapping("/classes")
    public CreateClassResult createClass(
            @RequestBody CreateClassRequest body,
            HttpServletRequest request) {
        return classroomAdminService.createClass(requireUserId(request), body);
    }

    @GetMapping("/classes")
    public List<ClassSummary> listClasses(HttpServletRequest request) {
        return classroomAdminService.listOwnedClasses(requireUserId(request));
    }

    @PostMapping("/terms/{termId}/invites")
    public Map<String, String> createInvite(
            @PathVariable String termId,
            @RequestBody(required = false) InviteCreateRequest body,
            HttpServletRequest request) {
        String label = body != null ? body.label() : null;
        InviteLinkService.IssuedInvite invite =
                classroomAdminService.createInvite(requireUserId(request), termId, label);
        return Map.of(
                "inviteLinkId", invite.inviteLinkId(),
                "code", invite.code(),
                "codeHint", invite.codeHint() != null ? invite.codeHint() : "",
                "termId", invite.termId()
        );
    }

    @PostMapping("/invites/redeem")
    public ResponseEntity<Map<String, Object>> redeemInvite(
            @RequestBody RedeemRequest body,
            HttpServletRequest request) {
        String userId = requireUserId(request);
        String code = body != null ? body.code() : null;
        RedeemResult result = inviteLinkService.redeem(code, userId);
        return mapRedeem(result);
    }

    @GetMapping("/terms/{termId}/features")
    public FeatureResponse getFeatures(@PathVariable String termId, HttpServletRequest request) {
        ClassFeatureSettingsEntity features =
                classroomAdminService.getFeatures(requireUserId(request), termId);
        return toFeatureResponse(features);
    }

    @PutMapping("/terms/{termId}/features")
    public FeatureResponse updateFeatures(
            @PathVariable String termId,
            @RequestBody FeatureUpdateRequest body,
            HttpServletRequest request) {
        ClassFeatureSettingsEntity features =
                classroomAdminService.updateFeatures(requireUserId(request), termId, body);
        return toFeatureResponse(features);
    }

    @GetMapping("/terms/{termId}/assignments")
    public List<AssignmentResponse> listAssignments(
            @PathVariable String termId,
            @RequestParam(name = "publishedOnly", defaultValue = "false") boolean publishedOnly,
            HttpServletRequest request) {
        return classroomAdminService.listAssignments(requireUserId(request), termId, publishedOnly).stream()
                .map(this::toAssignmentResponse)
                .toList();
    }

    @PostMapping("/terms/{termId}/assignments")
    public AssignmentResponse createAssignment(
            @PathVariable String termId,
            @RequestBody AssignmentWriteRequest body,
            HttpServletRequest request) {
        return toAssignmentResponse(
                classroomAdminService.createAssignment(requireUserId(request), termId, body));
    }

    @PutMapping("/assignments/{assignmentId}")
    public AssignmentResponse updateAssignment(
            @PathVariable String assignmentId,
            @RequestBody AssignmentWriteRequest body,
            HttpServletRequest request) {
        return toAssignmentResponse(
                classroomAdminService.updateAssignment(requireUserId(request), assignmentId, body));
    }

    @GetMapping("/terms/{termId}/roster")
    public List<EnrollmentRow> roster(@PathVariable String termId, HttpServletRequest request) {
        return classroomAdminService.listRoster(requireUserId(request), termId);
    }

    private ResponseEntity<Map<String, Object>> mapRedeem(RedeemResult result) {
        return switch (result.status()) {
            case SUCCESS, IDEMPOTENT -> ResponseEntity.ok(Map.of(
                    "status", result.status().name(),
                    "enrollmentId", result.enrollmentId() != null ? result.enrollmentId() : "",
                    "termId", result.termId() != null ? result.termId() : ""
            ));
            case UNAUTHENTICATED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", RedeemStatus.UNAUTHENTICATED.name()));
            case INVALID_CODE -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", RedeemStatus.INVALID_CODE.name()));
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("status", RedeemStatus.EXPIRED.name()));
            case REVOKED -> ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("status", RedeemStatus.REVOKED.name()));
            case TERM_NOT_ACTIVE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", RedeemStatus.TERM_NOT_ACTIVE.name()));
            case MAX_USES -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", RedeemStatus.MAX_USES.name()));
            case NOT_ELIGIBLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", RedeemStatus.NOT_ELIGIBLE.name()));
            case ALREADY_STAFF -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", RedeemStatus.ALREADY_STAFF.name()));
        };
    }

    private FeatureResponse toFeatureResponse(ClassFeatureSettingsEntity f) {
        return new FeatureResponse(
                f.getTermId(),
                f.isQuizEnabled(),
                f.isRecapEnabled(),
                f.isTtsEnabled(),
                f.isIllustrationEnabled(),
                f.isCharacterEnabled(),
                f.isChatEnabled(),
                f.isSpeedReadingEnabled(),
                f.isReadingBuddyEnabled()
        );
    }

    private AssignmentResponse toAssignmentResponse(AssignmentEntity a) {
        return new AssignmentResponse(
                a.getId(),
                a.getTermId(),
                a.getTitle(),
                a.getBookId(),
                a.getChapterId(),
                a.getChapterIndex(),
                a.getDueDate() != null ? a.getDueDate().toString() : null,
                a.getAvailableFromDate() != null ? a.getAvailableFromDate().toString() : null,
                a.isQuizRequired(),
                a.getSortOrder(),
                a.getStatus()
        );
    }

    private String resolveUserId(HttpServletRequest request) {
        return accountAuthService.resolveAuthenticatedPrincipal(request)
                .map(AccountAuthService.AccountPrincipal::userId)
                .orElse(null);
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = resolveUserId(request);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
        return userId;
    }

    public record InviteCreateRequest(String label) {
    }

    public record RedeemRequest(String code) {
    }

    public record FeatureResponse(
            String termId,
            boolean quizEnabled,
            boolean recapEnabled,
            boolean ttsEnabled,
            boolean illustrationEnabled,
            boolean characterEnabled,
            boolean chatEnabled,
            boolean speedReadingEnabled,
            boolean readingBuddyEnabled
    ) {
    }

    public record AssignmentResponse(
            String assignmentId,
            String termId,
            String title,
            String bookId,
            String chapterId,
            Integer chapterIndex,
            String dueDate,
            String availableFromDate,
            boolean quizRequired,
            int sortOrder,
            String status
    ) {
    }
}
