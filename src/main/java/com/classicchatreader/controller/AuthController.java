package com.classicchatreader.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.classicchatreader.service.PublicSessionAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final PublicSessionAuthService sessionAuthService;
    private final String deploymentMode;
    private final String publicApiKey;

    public AuthController(
            PublicSessionAuthService sessionAuthService,
            @Value("${deployment.mode:local}") String deploymentMode,
            @Value("${security.public.api-key:}") String publicApiKey) {
        this.sessionAuthService = sessionAuthService;
        this.deploymentMode = deploymentMode;
        this.publicApiKey = publicApiKey == null ? "" : publicApiKey;
    }

    @GetMapping("/status")
    public AuthStatusResponse getStatus(HttpServletRequest request) {
        return status(request, null, null);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthStatusResponse> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (!isPublicMode()) {
            return ResponseEntity.ok(status(httpRequest, "Auth is not required in local mode.", true));
        }
        if (!sessionAuthService.isPasswordConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(status(httpRequest, "Collaborator auth is not configured.", false));
        }
        if (request == null || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(status(httpRequest, "Password is required.", false));
        }
        if (!sessionAuthService.authenticatePassword(request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(status(httpRequest, "Invalid password.", false));
        }

        sessionAuthService.createSession(httpResponse);
        return ResponseEntity.ok(status(httpRequest, "Signed in.", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthStatusResponse> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionAuthService.clearSession(request, response);
        return ResponseEntity.ok(status(request, "Signed out.", false));
    }

    private AuthStatusResponse status(HttpServletRequest request, String message, Boolean authenticatedOverride) {
        boolean publicMode = isPublicMode();
        boolean authenticated = authenticatedOverride != null
                ? authenticatedOverride
                : sessionAuthService.isAuthenticated(request);
        boolean collaboratorAuthConfigured = sessionAuthService.isPasswordConfigured();
        boolean apiKeyAuthConfigured = !publicApiKey.isBlank();
        boolean authRequired = publicMode;
        boolean canAccessSensitive = !authRequired || authenticated;
        return new AuthStatusResponse(
                publicMode,
                authRequired,
                authenticated,
                canAccessSensitive,
                collaboratorAuthConfigured,
                apiKeyAuthConfigured,
                message
        );
    }

    private boolean isPublicMode() {
        return "public".equalsIgnoreCase(deploymentMode);
    }

    public record LoginRequest(String password) {
    }

    public record AuthStatusResponse(
            boolean publicMode,
            boolean authRequired,
            boolean authenticated,
            boolean canAccessSensitive,
            boolean collaboratorAuthConfigured,
            boolean apiKeyAuthConfigured,
            String message
    ) {
    }
}
