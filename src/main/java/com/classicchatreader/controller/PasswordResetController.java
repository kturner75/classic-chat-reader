package com.classicchatreader.controller;

import com.classicchatreader.service.AccountAuthAuditService;
import com.classicchatreader.service.AccountAuthRateLimiter;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Forgot password for reader accounts. The token reaches the page in the URL fragment and the
 * server only in POST bodies, never in a query string, so it stays out of access logs.
 */
@RestController
@RequestMapping("/api/account/password-reset")
public class PasswordResetController {

    static final String REQUEST_ACCEPTED_MESSAGE =
            "If an account uses that email, we've sent it a link to reset the password. Check your inbox and spam folder.";

    private final PasswordResetService passwordResetService;
    private final AccountAuthService accountAuthService;
    private final AccountAuthRateLimiter accountAuthRateLimiter;
    private final AccountAuthAuditService accountAuthAuditService;

    public PasswordResetController(
            PasswordResetService passwordResetService,
            AccountAuthService accountAuthService,
            AccountAuthRateLimiter accountAuthRateLimiter,
            AccountAuthAuditService accountAuthAuditService) {
        this.passwordResetService = passwordResetService;
        this.accountAuthService = accountAuthService;
        this.accountAuthRateLimiter = accountAuthRateLimiter;
        this.accountAuthAuditService = accountAuthAuditService;
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> requestReset(
            @RequestBody(required = false) ResetRequest body,
            HttpServletRequest request) {
        if (!accountAuthService.isAccountAuthEnabled()) {
            return disabled();
        }
        String email = body == null ? null : body.email();
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Enter the email you registered with."));
        }
        AccountAuthRateLimiter.RateLimitResult rateLimit = accountAuthRateLimiter.checkPasswordResetRequest(request, email);
        if (!rateLimit.allowed()) {
            accountAuthAuditService.record("password_reset_request", "rate_limited", request, email, null,
                    rateLimit.retryAfterSeconds(), rateLimit.scope());
            return rateLimited(rateLimit.retryAfterSeconds());
        }
        passwordResetService.requestReset(email);
        // The outcome is identical whether or not the email has an account.
        accountAuthAuditService.record("password_reset_request", "accepted", request, email, null, null, null);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(Map.of("message", REQUEST_ACCEPTED_MESSAGE));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(
            @RequestBody(required = false) TokenRequest body,
            HttpServletRequest request) {
        if (!accountAuthService.isAccountAuthEnabled()) {
            return disabled();
        }
        AccountAuthRateLimiter.RateLimitResult rateLimit = accountAuthRateLimiter.checkPasswordResetToken(request);
        if (!rateLimit.allowed()) {
            return rateLimited(rateLimit.retryAfterSeconds());
        }
        boolean valid = body != null && passwordResetService.isTokenValid(body.token());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("valid", valid));
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @RequestBody(required = false) CompleteRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!accountAuthService.isAccountAuthEnabled()) {
            return disabled();
        }
        if (body == null || body.token() == null || body.token().isBlank()
                || body.password() == null || body.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Enter a new password."));
        }
        AccountAuthRateLimiter.RateLimitResult rateLimit = accountAuthRateLimiter.checkPasswordResetToken(request);
        if (!rateLimit.allowed()) {
            accountAuthAuditService.record("password_reset_complete", "rate_limited", request, null, null,
                    rateLimit.retryAfterSeconds(), rateLimit.scope());
            return rateLimited(rateLimit.retryAfterSeconds());
        }
        AccountAuthService.AuthResult result = passwordResetService.completeReset(body.token(), body.password(), response);
        accountAuthAuditService.record("password_reset_complete", outcomeFor(result.status()), request,
                result.email(), null, null, null);
        HttpStatus status = switch (result.status()) {
            case SUCCESS -> HttpStatus.OK;
            case DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            case ROLLOUT_RESTRICTED -> HttpStatus.FORBIDDEN;
            case INVALID_PASSWORD -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.GONE;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("message", result.message() == null ? "" : result.message()));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Account auth is disabled."));
    }

    private ResponseEntity<Map<String, Object>> rateLimited(Integer retryAfterSeconds) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            builder.header("Retry-After", String.valueOf(retryAfterSeconds));
        }
        return builder.body(Map.of("message", "Too many password reset attempts. Please wait a minute and try again."));
    }

    private String outcomeFor(AccountAuthService.ResultStatus status) {
        return switch (status) {
            case SUCCESS -> "success";
            case DISABLED -> "disabled";
            case ROLLOUT_RESTRICTED -> "rollout_restricted";
            case INVALID_PASSWORD -> "invalid_password";
            default -> "invalid_token";
        };
    }

    public record ResetRequest(String email) {
    }

    public record TokenRequest(String token) {
    }

    public record CompleteRequest(String token, String password) {
    }
}
