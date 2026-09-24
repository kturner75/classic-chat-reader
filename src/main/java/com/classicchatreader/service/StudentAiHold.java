package com.classicchatreader.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Request-level check for the BL-043.3 student AI hold: endpoints that send a reader's text or audio
 * to an AI provider refuse signed-in enrolled students until {@code classroom.ferpa.student-ai-covered}
 * is set. See docs/product/subprocessors.md.
 */
@Component
public class StudentAiHold {

    public static final String ERROR_CODE = "CLASSROOM_AI_HELD";
    public static final String MESSAGE = "AI chat isn't available for classes yet.";

    private final AccountAuthService accountAuthService;
    private final ClassroomContextService classroomContextService;

    public StudentAiHold(AccountAuthService accountAuthService, ClassroomContextService classroomContextService) {
        this.accountAuthService = accountAuthService;
        this.classroomContextService = classroomContextService;
    }

    public boolean isHeld(HttpServletRequest request) {
        return accountAuthService.resolveAuthenticatedPrincipal(request)
                .map(principal -> classroomContextService.isStudentAiHeld(principal.userId()))
                .orElse(false);
    }
}
