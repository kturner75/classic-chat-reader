package com.classicchatreader.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.classicchatreader.config.GoogleAccountOAuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GoogleAccountOAuthService {

    private static final String GOOGLE_PROVIDER = "google";
    private static final String STATE_COOKIE = "pdr_account_google_state";
    private static final String VERIFIER_COOKIE = "pdr_account_google_verifier";
    private static final String NONCE_COOKIE = "pdr_account_google_nonce";
    private static final String RETURN_TO_COOKIE = "pdr_account_google_return_to";

    private final AccountAuthService accountAuthService;
    private final WebClient webClient;
    private final JwtDecoder jwtDecoder;
    private final boolean available;
    private final boolean secureCookie;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String authorizationUri;
    private final String tokenUri;
    private final Set<String> allowedIssuers;
    private final int requestTtlMinutes;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public GoogleAccountOAuthService(
            AccountAuthService accountAuthService,
            GoogleAccountOAuthProperties properties,
            WebClient.Builder webClientBuilder,
            @Value("${account.auth.secure-cookie:false}") boolean secureCookie) {
        this(
                accountAuthService,
                properties,
                webClientBuilder.build(),
                buildDecoder(properties),
                secureCookie
        );
    }

    GoogleAccountOAuthService(
            AccountAuthService accountAuthService,
            GoogleAccountOAuthProperties properties,
            WebClient webClient,
            JwtDecoder jwtDecoder,
            boolean secureCookie) {
        this.accountAuthService = accountAuthService;
        this.webClient = webClient;
        this.jwtDecoder = jwtDecoder;
        this.secureCookie = secureCookie;
        this.clientId = trimToNull(properties.getClientId());
        this.clientSecret = trimToNull(properties.getClientSecret());
        this.redirectUri = trimToNull(properties.getRedirectUri());
        this.authorizationUri = trimToNull(properties.getAuthorizationUri());
        this.tokenUri = trimToNull(properties.getTokenUri());
        this.requestTtlMinutes = Math.max(1, properties.getRequestTtlMinutes());
        this.allowedIssuers = normalizeIssuers(properties.getAllowedIssuers());
        this.available = properties.isEnabled()
                && this.clientId != null
                && this.clientSecret != null
                && this.redirectUri != null
                && this.authorizationUri != null
                && this.tokenUri != null
                && jwtDecoder != null;
    }

    public boolean isAvailable() {
        return available;
    }

    public AuthorizationStartResult beginAuthorization(String returnTo, HttpServletResponse response) {
        String normalizedReturnTo = sanitizeReturnTo(returnTo);
        if (!available) {
            return new AuthorizationStartResult(withNotice(normalizedReturnTo, "google_unavailable"), false);
        }

        String state = newToken(32);
        String codeVerifier = newToken(48);
        String nonce = newToken(32);

        writeTransientCookie(response, STATE_COOKIE, state);
        writeTransientCookie(response, VERIFIER_COOKIE, codeVerifier);
        writeTransientCookie(response, NONCE_COOKIE, nonce);
        writeTransientCookie(response, RETURN_TO_COOKIE, normalizedReturnTo);

        String codeChallenge = hashSha256Base64Url(codeVerifier);
        URI authorizationRedirect = UriComponentsBuilder.fromUriString(authorizationUri)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUri();
        return new AuthorizationStartResult(authorizationRedirect, true);
    }

    public AuthorizationCallbackResult completeAuthorization(
            String code,
            String returnedState,
            String providerError,
            HttpServletRequest request,
            HttpServletResponse response) {
        String returnTo = sanitizeReturnTo(readCookie(request, RETURN_TO_COOKIE));
        String expectedState = readCookie(request, STATE_COOKIE);
        String codeVerifier = readCookie(request, VERIFIER_COOKIE);
        String expectedNonce = readCookie(request, NONCE_COOKIE);
        clearTransientCookies(response);

        if (!available) {
            return AuthorizationCallbackResult.failure(withNotice(returnTo, "google_unavailable"), "unavailable");
        }

        if (providerError != null && !providerError.isBlank()) {
            String reason = "access_denied".equals(providerError) ? "cancelled" : "provider_error";
            String notice = "access_denied".equals(providerError) ? "google_cancelled" : "google_provider_error";
            return AuthorizationCallbackResult.failure(withNotice(returnTo, notice), reason);
        }

        if (!safeEquals(expectedState, returnedState)) {
            return AuthorizationCallbackResult.failure(withNotice(returnTo, "google_state_mismatch"), "state_mismatch");
        }
        if (code == null || code.isBlank() || codeVerifier == null || expectedNonce == null) {
            return AuthorizationCallbackResult.failure(withNotice(returnTo, "google_code_missing"), "code_missing");
        }

        GoogleTokenResponse tokenResponse;
        try {
            tokenResponse = exchangeCode(code, codeVerifier);
        } catch (RuntimeException ex) {
            return AuthorizationCallbackResult.failure(withNotice(returnTo, "google_token_exchange_failed"), "token_exchange_failed");
        }
        if (tokenResponse == null || tokenResponse.idToken() == null || tokenResponse.idToken().isBlank()) {
            return AuthorizationCallbackResult.failure(withNotice(returnTo, "google_token_exchange_failed"), "token_exchange_failed");
        }

        VerifiedGoogleIdentity identity;
        try {
            identity = verifyIdToken(tokenResponse.idToken(), expectedNonce);
        } catch (InvalidGoogleIdentityException ex) {
            return AuthorizationCallbackResult.failure(withNotice(returnTo, ex.noticeCode()), ex.reason());
        }

        AccountAuthService.AuthResult signInResult = accountAuthService.signInWithExternalIdentity(
                new AccountAuthService.ExternalIdentity(
                        GOOGLE_PROVIDER,
                        identity.subject(),
                        identity.email(),
                        identity.emailVerified()
                ),
                response
        );
        if (signInResult.status() != AccountAuthService.ResultStatus.SUCCESS) {
            return AuthorizationCallbackResult.failure(
                    withNotice(returnTo, mapFailureNotice(signInResult.status())),
                    mapFailureReason(signInResult.status()),
                    signInResult.email(),
                    signInResult.status()
            );
        }

        return AuthorizationCallbackResult.success(
                withNotice(returnTo, "google_signed_in"),
                signInResult.email(),
                signInResult.status()
        );
    }

    private GoogleTokenResponse exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("code", code);
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("redirect_uri", redirectUri);
        formData.add("grant_type", "authorization_code");
        formData.add("code_verifier", codeVerifier);

        return webClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(GoogleTokenResponse.class)
                .block(Duration.ofSeconds(15));
    }

    private VerifiedGoogleIdentity verifyIdToken(String idToken, String expectedNonce) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(idToken);
        } catch (JwtException ex) {
            throw new InvalidGoogleIdentityException("invalid_id_token", "google_id_token_invalid");
        }

        String subject = trimToNull(jwt.getSubject());
        String email = trimToNull(jwt.getClaimAsString("email"));
        String nonce = trimToNull(jwt.getClaimAsString("nonce"));
        boolean emailVerified = claimAsBoolean(jwt, "email_verified");
        String issuer = jwt.getIssuer() == null ? trimToNull(jwt.getClaimAsString("iss")) : trimToNull(jwt.getIssuer().toString());

        Instant now = Instant.now();
        if (subject == null || email == null || !emailVerified) {
            throw new InvalidGoogleIdentityException("invalid_email", "google_email_unverified");
        }
        if (!safeEquals(expectedNonce, nonce)) {
            throw new InvalidGoogleIdentityException("nonce_mismatch", "google_id_token_invalid");
        }
        if (issuer == null || !allowedIssuers.contains(issuer.toLowerCase(Locale.ROOT))) {
            throw new InvalidGoogleIdentityException("invalid_issuer", "google_id_token_invalid");
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(clientId)) {
            throw new InvalidGoogleIdentityException("invalid_audience", "google_id_token_invalid");
        }
        if (jwt.getExpiresAt() == null || jwt.getExpiresAt().isBefore(now.minusSeconds(30))) {
            throw new InvalidGoogleIdentityException("token_expired", "google_id_token_invalid");
        }
        if (jwt.getIssuedAt() != null && jwt.getIssuedAt().isAfter(now.plusSeconds(300))) {
            throw new InvalidGoogleIdentityException("token_issued_in_future", "google_id_token_invalid");
        }

        return new VerifiedGoogleIdentity(subject, email, true);
    }

    private boolean claimAsBoolean(Jwt jwt, String claimName) {
        Object value = jwt.getClaims().get(claimName);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    private void writeTransientCookie(HttpServletResponse response, String name, String value) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMinutes(requestTtlMinutes))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearTransientCookies(HttpServletResponse response) {
        clearTransientCookie(response, STATE_COOKIE);
        clearTransientCookie(response, VERIFIER_COOKIE);
        clearTransientCookie(response, NONCE_COOKIE);
        clearTransientCookie(response, RETURN_TO_COOKIE);
    }

    private void clearTransientCookie(HttpServletResponse response, String name) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String sanitizeReturnTo(String returnTo) {
        String candidate = trimToNull(returnTo);
        if (candidate == null) {
            return "/";
        }
        if (!candidate.startsWith("/") || candidate.startsWith("//") || candidate.contains("\r") || candidate.contains("\n")) {
            return "/";
        }
        return candidate;
    }

    private URI withNotice(String returnTo, String noticeCode) {
        String sanitized = sanitizeReturnTo(returnTo);
        int fragmentIndex = sanitized.indexOf('#');
        String withoutFragment = fragmentIndex >= 0 ? sanitized.substring(0, fragmentIndex) : sanitized;
        String fragment = fragmentIndex >= 0 ? sanitized.substring(fragmentIndex) : "";
        String separator = withoutFragment.contains("?") ? "&" : "?";
        return URI.create(withoutFragment + separator + "account_notice=" + noticeCode + fragment);
    }

    private String newToken(int bytes) {
        byte[] raw = new byte[bytes];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String hashSha256Base64Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash OAuth verifier", ex);
        }
    }

    private boolean safeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String mapFailureNotice(AccountAuthService.ResultStatus status) {
        return switch (status) {
            case DISABLED -> "google_unavailable";
            case ROLLOUT_RESTRICTED -> "google_rollout_restricted";
            case EXTERNAL_IDENTITY_ERROR, INVALID_EMAIL -> "google_email_unverified";
            default -> "google_signin_failed";
        };
    }

    private String mapFailureReason(AccountAuthService.ResultStatus status) {
        return switch (status) {
            case DISABLED -> "unavailable";
            case ROLLOUT_RESTRICTED -> "rollout_restricted";
            case EXTERNAL_IDENTITY_ERROR, INVALID_EMAIL -> "invalid_identity";
            default -> "signin_failed";
        };
    }

    private static JwtDecoder buildDecoder(GoogleAccountOAuthProperties properties) {
        String jwkSetUri = trimToNull(properties.getJwkSetUri());
        if (!properties.isEnabled() || jwkSetUri == null) {
            return null;
        }
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Set<String> normalizeIssuers(List<String> issuers) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (issuers != null) {
            for (String issuer : issuers) {
                String value = trimToNull(issuer);
                if (value != null) {
                    normalized.add(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.add("https://accounts.google.com");
            normalized.add("accounts.google.com");
        }
        return Set.copyOf(normalized);
    }

    public record AuthorizationStartResult(URI redirectUri, boolean available) {
    }

    public record AuthorizationCallbackResult(
            URI redirectUri,
            boolean authenticated,
            String failureReason,
            String email,
            AccountAuthService.ResultStatus accountStatus
    ) {
        static AuthorizationCallbackResult success(
                URI redirectUri,
                String email,
                AccountAuthService.ResultStatus accountStatus) {
            return new AuthorizationCallbackResult(redirectUri, true, null, email, accountStatus);
        }

        static AuthorizationCallbackResult failure(URI redirectUri, String failureReason) {
            return new AuthorizationCallbackResult(redirectUri, false, failureReason, null, null);
        }

        static AuthorizationCallbackResult failure(
                URI redirectUri,
                String failureReason,
                String email,
                AccountAuthService.ResultStatus accountStatus) {
            return new AuthorizationCallbackResult(redirectUri, false, failureReason, email, accountStatus);
        }
    }

    record GoogleTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            String scope
    ) {
    }

    record VerifiedGoogleIdentity(String subject, String email, boolean emailVerified) {
    }

    static class InvalidGoogleIdentityException extends RuntimeException {
        private final String reason;
        private final String noticeCode;

        InvalidGoogleIdentityException(String reason, String noticeCode) {
            this.reason = reason;
            this.noticeCode = noticeCode;
        }

        String reason() {
            return reason;
        }

        String noticeCode() {
            return noticeCode;
        }
    }
}
