package com.classicchatreader.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.classicchatreader.entity.PendingExternalIdentityLinkEntity;
import com.classicchatreader.entity.UserAuthIdentityEntity;
import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.entity.UserLocalCredentialEntity;
import com.classicchatreader.entity.UserSessionEntity;
import com.classicchatreader.repository.PendingExternalIdentityLinkRepository;
import com.classicchatreader.repository.UserAuthIdentityRepository;
import com.classicchatreader.repository.UserLocalCredentialRepository;
import com.classicchatreader.repository.UserRepository;
import com.classicchatreader.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class AccountAuthService {

    private static final Logger log = LoggerFactory.getLogger(AccountAuthService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final String PENDING_LINK_COOKIE = "pdr_account_google_link";
    private static final int PENDING_LINK_TTL_MINUTES = 10;

    private final UserRepository userRepository;
    private final UserLocalCredentialRepository userLocalCredentialRepository;
    private final UserAuthIdentityRepository userAuthIdentityRepository;
    private final UserSessionRepository userSessionRepository;
    private final PendingExternalIdentityLinkRepository pendingExternalIdentityLinkRepository;
    private final boolean enabled;
    private final RolloutMode rolloutMode;
    private final Set<String> rolloutAllowedEmails;
    private final String cookieName;
    private final int sessionTtlMinutes;
    private final boolean secureCookie;
    private final int minPasswordLength;
    private final int bcryptStrength;
    private final int loginLockoutThreshold;
    private final int loginLockoutBaseDelaySeconds;
    private final int loginLockoutMaxDelaySeconds;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicInteger cleanupTicker = new AtomicInteger();

    public AccountAuthService(
            UserRepository userRepository,
            UserLocalCredentialRepository userLocalCredentialRepository,
            UserAuthIdentityRepository userAuthIdentityRepository,
            UserSessionRepository userSessionRepository,
            PendingExternalIdentityLinkRepository pendingExternalIdentityLinkRepository,
            boolean enabled,
            String rolloutModeRaw,
            String rolloutAllowedEmailsRaw,
            String cookieName,
            int sessionTtlMinutes,
            boolean secureCookie,
            int minPasswordLength,
            int bcryptStrength) {
        this(
                userRepository,
                userLocalCredentialRepository,
                userAuthIdentityRepository,
                userSessionRepository,
                pendingExternalIdentityLinkRepository,
                enabled,
                rolloutModeRaw,
                rolloutAllowedEmailsRaw,
                cookieName,
                sessionTtlMinutes,
                secureCookie,
                minPasswordLength,
                bcryptStrength,
                5,
                30,
                900
        );
    }

    @Autowired
    public AccountAuthService(
            UserRepository userRepository,
            UserLocalCredentialRepository userLocalCredentialRepository,
            UserAuthIdentityRepository userAuthIdentityRepository,
            UserSessionRepository userSessionRepository,
            PendingExternalIdentityLinkRepository pendingExternalIdentityLinkRepository,
            @Value("${account.auth.enabled:false}") boolean enabled,
            @Value("${account.auth.rollout.mode:optional}") String rolloutModeRaw,
            @Value("${account.auth.rollout.allowed-emails:}") String rolloutAllowedEmailsRaw,
            @Value("${account.auth.cookie-name:pdr_account_session}") String cookieName,
            @Value("${account.auth.session.ttl-minutes:43200}") int sessionTtlMinutes,
            @Value("${account.auth.secure-cookie:false}") boolean secureCookie,
            @Value("${account.auth.password.min-length:10}") int minPasswordLength,
            @Value("${account.auth.password.bcrypt-strength:12}") int bcryptStrength,
            @Value("${account.auth.login.lockout.threshold:5}") int loginLockoutThreshold,
            @Value("${account.auth.login.lockout.base-delay-seconds:30}") int loginLockoutBaseDelaySeconds,
            @Value("${account.auth.login.lockout.max-delay-seconds:900}") int loginLockoutMaxDelaySeconds) {
        this.userRepository = userRepository;
        this.userLocalCredentialRepository = userLocalCredentialRepository;
        this.userAuthIdentityRepository = userAuthIdentityRepository;
        this.userSessionRepository = userSessionRepository;
        this.pendingExternalIdentityLinkRepository = pendingExternalIdentityLinkRepository;
        this.enabled = enabled;
        this.rolloutMode = RolloutMode.fromConfig(rolloutModeRaw);
        this.rolloutAllowedEmails = parseAllowedEmails(rolloutAllowedEmailsRaw);
        this.cookieName = (cookieName == null || cookieName.isBlank()) ? "pdr_account_session" : cookieName;
        this.sessionTtlMinutes = Math.max(15, sessionTtlMinutes);
        this.secureCookie = secureCookie;
        this.minPasswordLength = Math.max(8, minPasswordLength);
        this.bcryptStrength = Math.min(14, Math.max(10, bcryptStrength));
        this.loginLockoutThreshold = Math.max(1, loginLockoutThreshold);
        this.loginLockoutBaseDelaySeconds = Math.max(1, loginLockoutBaseDelaySeconds);
        this.loginLockoutMaxDelaySeconds = Math.max(this.loginLockoutBaseDelaySeconds, loginLockoutMaxDelaySeconds);
        log.info(
                "Reader account auth initialized: enabled={}, rolloutMode={}, allowListSize={}, lockoutThreshold={}, lockoutBaseDelaySeconds={}, lockoutMaxDelaySeconds={}",
                enabled,
                this.rolloutMode.value(),
                this.rolloutAllowedEmails.size(),
                this.loginLockoutThreshold,
                this.loginLockoutBaseDelaySeconds,
                this.loginLockoutMaxDelaySeconds);
    }

    @Transactional
    public AuthResult register(String email, String password, HttpServletResponse response) {
        if (!isRolloutEnabled()) {
            return disabledResult();
        }

        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail == null) {
            return AuthResult.error(ResultStatus.INVALID_EMAIL, enabled, "A valid email address is required.");
        }
        if (!isValidPassword(password)) {
            return AuthResult.error(
                    ResultStatus.INVALID_PASSWORD,
                    enabled,
                    "Password must be at least " + minPasswordLength + " characters.");
        }
        if (!isEmailAllowedForRollout(normalizedEmail)) {
            return rolloutRestrictedResult();
        }

        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            return AuthResult.error(ResultStatus.EMAIL_ALREADY_EXISTS, enabled, "Email is already registered.");
        }

        try {
            UserEntity user = createUser(normalizedEmail);
            createLocalCredentials(user, password);
            createSession(user, response);
            cleanupIfNeeded();
            return AuthResult.success(enabled, user.getEmail(), "Account created.");
        } catch (DataIntegrityViolationException e) {
            return AuthResult.error(ResultStatus.EMAIL_ALREADY_EXISTS, enabled, "Email is already registered.");
        }
    }

    @Transactional
    public AuthResult login(String email, String password, HttpServletResponse response) {
        if (!isRolloutEnabled()) {
            return disabledResult();
        }

        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail == null || password == null || password.isBlank()) {
            return AuthResult.error(ResultStatus.INVALID_CREDENTIALS, enabled, "Invalid email or password.");
        }
        if (!isEmailAllowedForRollout(normalizedEmail)) {
            return rolloutRestrictedResult();
        }

        Optional<UserEntity> userOptional = userRepository.findByEmail(normalizedEmail);
        if (userOptional.isEmpty()) {
            return AuthResult.error(ResultStatus.INVALID_CREDENTIALS, enabled, "Invalid email or password.");
        }

        UserEntity user = userOptional.get();
        Optional<UserLocalCredentialEntity> credentialOptional = userLocalCredentialRepository.findByUserId(user.getId());
        if (credentialOptional.isEmpty()) {
            return AuthResult.error(ResultStatus.INVALID_CREDENTIALS, enabled, "Invalid email or password.");
        }

        UserLocalCredentialEntity credential = credentialOptional.get();
        LocalDateTime now = LocalDateTime.now();
        AuthResult lockedResult = lockoutResultIfLocked(credential, now);
        if (lockedResult != null) {
            return lockedResult;
        }

        if (!BCrypt.checkpw(password, credential.getPasswordHash())) {
            return recordInvalidCredentials(credential, now);
        }

        clearLockoutStateIfNeeded(credential);
        createSession(user, response);
        cleanupIfNeeded();
        return AuthResult.success(enabled, user.getEmail(), "Signed in.");
    }

    @Transactional
    public AuthResult signInWithExternalIdentity(ExternalIdentity externalIdentity, HttpServletResponse response) {
        if (!isRolloutEnabled()) {
            return disabledResult();
        }

        if (externalIdentity == null) {
            return AuthResult.error(ResultStatus.EXTERNAL_IDENTITY_ERROR, enabled, "External sign-in could not be verified.");
        }

        String provider = normalizeProvider(externalIdentity.provider());
        String providerSubject = trimToNull(externalIdentity.providerSubject());
        String normalizedEmail = normalizeEmail(externalIdentity.email());
        if (provider == null || providerSubject == null || normalizedEmail == null || !externalIdentity.emailVerified()) {
            return AuthResult.error(
                    ResultStatus.EXTERNAL_IDENTITY_ERROR,
                    enabled,
                    "External sign-in requires a verified email address."
            );
        }
        if (!isEmailAllowedForRollout(normalizedEmail)) {
            return rolloutRestrictedResult();
        }

        Optional<UserAuthIdentityEntity> existingIdentity =
                userAuthIdentityRepository.findByProviderAndProviderSubject(provider, providerSubject);
        if (existingIdentity.isPresent()) {
            UserAuthIdentityEntity identity = existingIdentity.get();
            syncExternalIdentity(identity, normalizedEmail, true);
            createSession(identity.getUser(), response);
            cleanupIfNeeded();
            return AuthResult.success(enabled, identity.getUser().getEmail(), "Signed in.");
        }

        Optional<UserEntity> existingUser = userRepository.findByEmail(normalizedEmail);
        if (existingUser.isEmpty()) {
            UserEntity user = createUser(normalizedEmail);
            attachExternalIdentity(user, provider, providerSubject, normalizedEmail, true);
            createSession(user, response);
            cleanupIfNeeded();
            return AuthResult.success(enabled, user.getEmail(), "Signed in.");
        }

        UserEntity user = existingUser.get();
        if (userLocalCredentialRepository.findByUserId(user.getId()).isEmpty()) {
            attachExternalIdentity(user, provider, providerSubject, normalizedEmail, true);
            createSession(user, response);
            cleanupIfNeeded();
            return AuthResult.success(enabled, user.getEmail(), "Signed in.");
        }

        createPendingExternalIdentityLink(user, provider, providerSubject, normalizedEmail, response);
        cleanupIfNeeded();
        log.info("Refused silent {} identity link for an existing password account", provider);
        return new AuthResult(
                ResultStatus.EXTERNAL_IDENTITY_LINK_REQUIRED,
                enabled,
                false,
                user.getEmail(),
                "This Google email already has a password account. Enter that password to link Google.",
                null
        );
    }

    @Transactional
    public AuthResult confirmExternalIdentityLink(
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!isRolloutEnabled()) {
            return disabledResult();
        }
        if (password == null || password.isBlank()) {
            return AuthResult.error(ResultStatus.INVALID_CREDENTIALS, enabled, "Invalid email or password.");
        }

        Optional<PendingExternalIdentityLinkEntity> pendingOptional = resolvePendingExternalIdentityLink(request);
        if (pendingOptional.isEmpty()) {
            return AuthResult.error(
                    ResultStatus.EXTERNAL_IDENTITY_ERROR,
                    enabled,
                    "Google link confirmation expired. Sign in with Google again."
            );
        }

        PendingExternalIdentityLinkEntity pending = pendingOptional.get();
        if (!isEmailAllowedForRollout(pending.getEmail())) {
            clearPendingExternalIdentityLink(pending, response);
            return rolloutRestrictedResult();
        }

        Optional<UserEntity> userOptional = userRepository.findById(pending.getUserId());
        if (userOptional.isEmpty()) {
            clearPendingExternalIdentityLink(pending, response);
            return AuthResult.error(
                    ResultStatus.EXTERNAL_IDENTITY_ERROR,
                    enabled,
                    "Google link confirmation expired. Sign in with Google again."
            );
        }

        UserEntity user = userOptional.get();
        Optional<UserLocalCredentialEntity> credentialOptional = userLocalCredentialRepository.findByUserId(user.getId());
        if (credentialOptional.isEmpty()) {
            clearPendingExternalIdentityLink(pending, response);
            return AuthResult.error(ResultStatus.INVALID_CREDENTIALS, enabled, "Invalid email or password.");
        }

        UserLocalCredentialEntity credential = credentialOptional.get();
        LocalDateTime now = LocalDateTime.now();
        AuthResult lockedResult = lockoutResultIfLocked(credential, now);
        if (lockedResult != null) {
            return lockedResult;
        }

        if (!BCrypt.checkpw(password, credential.getPasswordHash())) {
            return recordInvalidCredentials(credential, now);
        }

        clearLockoutStateIfNeeded(credential);
        AuthResult linked = attachExternalIdentity(
                user,
                pending.getProvider(),
                pending.getProviderSubject(),
                pending.getEmail(),
                true
        );
        if (linked != null) {
            clearPendingExternalIdentityLink(pending, response);
            return linked;
        }

        invalidateSessions(user);
        clearPendingExternalIdentityLink(pending, response);
        createSession(user, response);
        cleanupIfNeeded();
        log.info("Linked {} identity after password confirmation", pending.getProvider());
        return AuthResult.success(enabled, user.getEmail(), "Google is linked to your account.");
    }

    @Transactional
    public boolean hasPendingExternalIdentityLink(HttpServletRequest request) {
        return pendingExternalIdentityLinkEmail(request).isPresent();
    }

    @Transactional
    public Optional<String> pendingExternalIdentityLinkEmail(HttpServletRequest request) {
        return resolvePendingExternalIdentityLink(request).map(PendingExternalIdentityLinkEntity::getEmail);
    }

    @Transactional
    public AuthResult logout(HttpServletRequest request, HttpServletResponse response) {
        if (!isRolloutEnabled()) {
            writeSessionCookie(response, "", 0);
            return new AuthResult(ResultStatus.DISABLED, false, false, null, "Account auth is disabled.");
        }

        String token = readToken(request);
        if (token != null && !token.isBlank()) {
            userSessionRepository.deleteByTokenHash(hashToken(token));
        }
        writeSessionCookie(response, "", 0);
        cleanupIfNeeded();
        return new AuthResult(ResultStatus.SUCCESS, true, false, null, "Signed out.");
    }

    @Transactional
    public AuthResult status(HttpServletRequest request) {
        if (!isRolloutEnabled()) {
            return new AuthResult(ResultStatus.DISABLED, false, false, null, "Account auth is disabled.");
        }

        Optional<AccountPrincipal> principal = resolveAuthenticatedPrincipalInternal(request);
        if (principal.isEmpty()) {
            cleanupIfNeeded();
            return new AuthResult(ResultStatus.SUCCESS, true, false, null, null);
        }
        cleanupIfNeeded();
        return AuthResult.success(true, principal.get().email(), null);
    }

    @Transactional
    public Optional<AccountPrincipal> resolveAuthenticatedPrincipal(HttpServletRequest request) {
        if (!isRolloutEnabled()) {
            return Optional.empty();
        }
        return resolveAuthenticatedPrincipalInternal(request);
    }

    private Optional<AccountPrincipal> resolveAuthenticatedPrincipalInternal(HttpServletRequest request) {
        String token = readToken(request);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = hashToken(token);
        Optional<UserSessionEntity> sessionOptional = userSessionRepository.findByTokenHash(tokenHash);
        if (sessionOptional.isEmpty()) {
            return Optional.empty();
        }

        UserSessionEntity session = sessionOptional.get();
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            userSessionRepository.deleteByTokenHash(tokenHash);
            return Optional.empty();
        }

        UserEntity user = session.getUser();
        if (!isEmailAllowedForRollout(user.getEmail())) {
            userSessionRepository.deleteByTokenHash(tokenHash);
            return Optional.empty();
        }
        return Optional.of(new AccountPrincipal(user.getId(), user.getEmail()));
    }

    private AuthResult disabledResult() {
        return new AuthResult(ResultStatus.DISABLED, false, false, null, "Account auth is disabled.");
    }

    private AuthResult rolloutRestrictedResult() {
        return AuthResult.error(
                ResultStatus.ROLLOUT_RESTRICTED,
                true,
                "Account access is currently limited to internal rollout users.");
    }

    private AuthResult lockoutResultIfLocked(UserLocalCredentialEntity credential, LocalDateTime now) {
        LocalDateTime lockedUntil = credential.getLoginLockedUntil();
        if (lockedUntil == null) {
            return null;
        }
        if (!lockedUntil.isAfter(now)) {
            credential.setLoginLockedUntil(null);
            userLocalCredentialRepository.save(credential);
            return null;
        }
        int retryAfterSeconds = retryAfterSeconds(now, lockedUntil);
        return AuthResult.error(
                ResultStatus.ACCOUNT_LOCKED,
                enabled,
                "Too many failed sign-in attempts. Please try again later.",
                retryAfterSeconds
        );
    }

    private AuthResult recordInvalidCredentials(UserLocalCredentialEntity credential, LocalDateTime now) {
        int attempts = Math.max(0, credential.getFailedLoginAttempts()) + 1;
        credential.setFailedLoginAttempts(attempts);

        if (attempts >= loginLockoutThreshold) {
            int lockoutSeconds = calculateLockoutSeconds(attempts);
            credential.setLoginLockedUntil(now.plusSeconds(lockoutSeconds));
            userLocalCredentialRepository.save(credential);
            return AuthResult.error(
                    ResultStatus.ACCOUNT_LOCKED,
                    enabled,
                    "Too many failed sign-in attempts. Please try again later.",
                    lockoutSeconds
            );
        }

        credential.setLoginLockedUntil(null);
        userLocalCredentialRepository.save(credential);
        return AuthResult.error(ResultStatus.INVALID_CREDENTIALS, enabled, "Invalid email or password.");
    }

    private void clearLockoutStateIfNeeded(UserLocalCredentialEntity credential) {
        if (credential.getFailedLoginAttempts() == 0 && credential.getLoginLockedUntil() == null) {
            return;
        }
        credential.setFailedLoginAttempts(0);
        credential.setLoginLockedUntil(null);
        userLocalCredentialRepository.save(credential);
    }

    private int calculateLockoutSeconds(int failedAttempts) {
        int exponent = Math.max(0, failedAttempts - loginLockoutThreshold);
        long multiplier = 1L << Math.min(20, exponent);
        long seconds = loginLockoutBaseDelaySeconds * multiplier;
        if (seconds > loginLockoutMaxDelaySeconds) {
            seconds = loginLockoutMaxDelaySeconds;
        }
        return Math.toIntExact(seconds);
    }

    private int retryAfterSeconds(LocalDateTime now, LocalDateTime lockedUntil) {
        long seconds = Duration.between(now, lockedUntil).getSeconds();
        if (seconds <= 0) {
            return 1;
        }
        if (seconds > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) seconds;
    }

    private AuthResult attachExternalIdentity(
            UserEntity user,
            String provider,
            String providerSubject,
            String normalizedEmail,
            boolean emailVerified) {
        UserAuthIdentityEntity identity = new UserAuthIdentityEntity();
        identity.setUser(user);
        identity.setProvider(provider);
        identity.setProviderSubject(providerSubject);
        identity.setEmail(normalizedEmail);
        identity.setEmailVerified(emailVerified);

        try {
            userAuthIdentityRepository.save(identity);
            return null;
        } catch (DataIntegrityViolationException e) {
            Optional<UserAuthIdentityEntity> existing =
                    userAuthIdentityRepository.findByProviderAndProviderSubject(provider, providerSubject);
            if (existing.isPresent() && user.getId().equals(existing.get().getUser().getId())) {
                syncExternalIdentity(existing.get(), normalizedEmail, emailVerified);
                return null;
            }
            return AuthResult.error(
                    ResultStatus.EXTERNAL_IDENTITY_ERROR,
                    enabled,
                    "This Google account is already linked to a different reader account."
            );
        }
    }

    private void createPendingExternalIdentityLink(
            UserEntity user,
            String provider,
            String providerSubject,
            String normalizedEmail,
            HttpServletResponse response) {
        pendingExternalIdentityLinkRepository.deleteByUserId(user.getId());

        String token = newToken();
        LocalDateTime now = LocalDateTime.now();
        PendingExternalIdentityLinkEntity pending = new PendingExternalIdentityLinkEntity();
        pending.setTokenHash(hashToken(token));
        pending.setUserId(user.getId());
        pending.setProvider(provider);
        pending.setProviderSubject(providerSubject);
        pending.setEmail(normalizedEmail);
        pending.setCreatedAt(now);
        pending.setExpiresAt(now.plusMinutes(PENDING_LINK_TTL_MINUTES));
        pendingExternalIdentityLinkRepository.save(pending);

        writeNamedCookie(
                response,
                PENDING_LINK_COOKIE,
                token,
                Math.toIntExact(Duration.ofMinutes(PENDING_LINK_TTL_MINUTES).getSeconds())
        );
    }

    private Optional<PendingExternalIdentityLinkEntity> resolvePendingExternalIdentityLink(HttpServletRequest request) {
        String token = readNamedCookie(request, PENDING_LINK_COOKIE);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        Optional<PendingExternalIdentityLinkEntity> pendingOptional =
                pendingExternalIdentityLinkRepository.findByTokenHash(hashToken(token));
        if (pendingOptional.isEmpty()) {
            return Optional.empty();
        }

        PendingExternalIdentityLinkEntity pending = pendingOptional.get();
        if (pending.getExpiresAt() == null || pending.getExpiresAt().isBefore(LocalDateTime.now())) {
            pendingExternalIdentityLinkRepository.deleteByTokenHash(pending.getTokenHash());
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    private void clearPendingExternalIdentityLink(
            PendingExternalIdentityLinkEntity pending,
            HttpServletResponse response) {
        if (pending != null && pending.getTokenHash() != null) {
            pendingExternalIdentityLinkRepository.deleteByTokenHash(pending.getTokenHash());
        }
        writeNamedCookie(response, PENDING_LINK_COOKIE, "", 0);
    }

    private void invalidateSessions(UserEntity user) {
        if (user == null || user.getId() == null) {
            return;
        }
        userSessionRepository.deleteByUser_Id(user.getId());
    }

    private void syncExternalIdentity(UserAuthIdentityEntity identity, String normalizedEmail, boolean emailVerified) {
        boolean identityChanged = false;
        if (!normalizedEmail.equals(identity.getEmail())) {
            identity.setEmail(normalizedEmail);
            identityChanged = true;
        }
        if (identity.isEmailVerified() != emailVerified) {
            identity.setEmailVerified(emailVerified);
            identityChanged = true;
        }
        if (identityChanged) {
            userAuthIdentityRepository.save(identity);
        }

        UserEntity user = identity.getUser();
        if (user != null && !normalizedEmail.equals(user.getEmail())) {
            Optional<UserEntity> conflictingUser = userRepository.findByEmail(normalizedEmail);
            if (conflictingUser.isEmpty() || user.getId().equals(conflictingUser.get().getId())) {
                user.setEmail(normalizedEmail);
                userRepository.save(user);
            }
        }
    }

    private UserEntity createUser(String normalizedEmail) {
        UserEntity user = new UserEntity();
        user.setEmail(normalizedEmail);
        return userRepository.save(user);
    }

    private void createLocalCredentials(UserEntity user, String password) {
        UserLocalCredentialEntity credentials = new UserLocalCredentialEntity();
        credentials.setUser(user);
        credentials.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt(bcryptStrength)));
        credentials.setFailedLoginAttempts(0);
        credentials.setLoginLockedUntil(null);
        userLocalCredentialRepository.save(credentials);
    }

    private void createSession(UserEntity user, HttpServletResponse response) {
        String token = newToken();
        String tokenHash = hashToken(token);
        LocalDateTime now = LocalDateTime.now();

        UserSessionEntity session = new UserSessionEntity();
        session.setUser(user);
        session.setTokenHash(tokenHash);
        session.setCreatedAt(now);
        session.setLastAccessedAt(now);
        session.setExpiresAt(now.plusMinutes(sessionTtlMinutes));
        userSessionRepository.save(session);

        int maxAgeSeconds = Math.toIntExact(Duration.ofMinutes(sessionTtlMinutes).getSeconds());
        writeNamedCookie(response, cookieName, token, maxAgeSeconds);
    }

    private void writeSessionCookie(HttpServletResponse response, String value, int maxAgeSeconds) {
        writeNamedCookie(response, cookieName, value, maxAgeSeconds);
    }

    private void writeNamedCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readToken(HttpServletRequest request) {
        return readNamedCookie(request, cookieName);
    }

    private String readNamedCookie(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean isValidPassword(String password) {
        return password != null && password.length() >= minPasswordLength;
    }

    public boolean isAccountAuthEnabled() {
        return isRolloutEnabled();
    }

    public String getRolloutMode() {
        return rolloutMode.value();
    }

    public boolean isAccountRequired() {
        return isRolloutEnabled() && rolloutMode == RolloutMode.REQUIRED;
    }

    private boolean isRolloutEnabled() {
        return enabled && rolloutMode != RolloutMode.DISABLED;
    }

    private boolean isEmailAllowedForRollout(String normalizedEmail) {
        if (rolloutMode != RolloutMode.INTERNAL) {
            return true;
        }
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return false;
        }
        if (rolloutAllowedEmails.isEmpty()) {
            return false;
        }
        return rolloutAllowedEmails.contains(normalizedEmail);
    }

    private Set<String> parseAllowedEmails(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        String[] parts = raw.split(",");
        for (String part : parts) {
            String normalized = normalizeEmail(part);
            if (normalized != null) {
                parsed.add(normalized);
            }
        }
        return Set.copyOf(parsed);
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 320) {
            return null;
        }
        return EMAIL_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    private String normalizeProvider(String provider) {
        String normalized = trimToNull(provider);
        if (normalized == null || normalized.length() > 64) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String newToken() {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            return Integer.toHexString(token.hashCode());
        }
    }

    private void cleanupIfNeeded() {
        int tick = cleanupTicker.incrementAndGet();
        if ((tick & 0xFF) != 0) {
            return;
        }
        userSessionRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        pendingExternalIdentityLinkRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }

    public enum ResultStatus {
        SUCCESS,
        DISABLED,
        ROLLOUT_RESTRICTED,
        INVALID_EMAIL,
        INVALID_PASSWORD,
        INVALID_CREDENTIALS,
        ACCOUNT_LOCKED,
        EMAIL_ALREADY_EXISTS,
        EXTERNAL_IDENTITY_ERROR,
        EXTERNAL_IDENTITY_LINK_REQUIRED
    }

    enum RolloutMode {
        DISABLED("disabled"),
        INTERNAL("internal"),
        OPTIONAL("optional"),
        REQUIRED("required");

        private final String value;

        RolloutMode(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        static RolloutMode fromConfig(String raw) {
            if (raw == null || raw.isBlank()) {
                return OPTIONAL;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "disabled" -> DISABLED;
                case "internal" -> INTERNAL;
                case "optional" -> OPTIONAL;
                case "required" -> REQUIRED;
                default -> OPTIONAL;
            };
        }
    }

    public record AuthResult(
            ResultStatus status,
            boolean accountAuthEnabled,
            boolean authenticated,
            String email,
            String message,
            Integer retryAfterSeconds
    ) {
        public AuthResult(
                ResultStatus status,
                boolean accountAuthEnabled,
                boolean authenticated,
                String email,
                String message) {
            this(status, accountAuthEnabled, authenticated, email, message, null);
        }

        public static AuthResult success(boolean accountAuthEnabled, String email, String message) {
            return new AuthResult(ResultStatus.SUCCESS, accountAuthEnabled, true, email, message, null);
        }

        public static AuthResult error(ResultStatus status, boolean accountAuthEnabled, String message) {
            return new AuthResult(status, accountAuthEnabled, false, null, message, null);
        }

        public static AuthResult error(
                ResultStatus status,
                boolean accountAuthEnabled,
                String message,
                Integer retryAfterSeconds) {
            return new AuthResult(status, accountAuthEnabled, false, null, message, retryAfterSeconds);
        }
    }

    public record AccountPrincipal(String userId, String email) {
    }

    public record ExternalIdentity(
            String provider,
            String providerSubject,
            String email,
            boolean emailVerified
    ) {
    }
}
