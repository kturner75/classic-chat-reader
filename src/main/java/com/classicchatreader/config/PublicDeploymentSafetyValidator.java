package com.classicchatreader.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Fail closed on production-like profiles (BL-043.1 / BL-043.12, SECURITY_AUDIT C-01 / H-07).
 *
 * <p>Local and test profiles stay open so {@code deployment.mode=local} remains the default
 * developer path. {@code prod} and {@code mariadb} cannot boot in local mode, without Secure
 * cookies, or without collaborator/API-key material.
 */
@Component
public class PublicDeploymentSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(PublicDeploymentSafetyValidator.class);

    private final Environment environment;
    private final String deploymentMode;
    private final String publicApiKey;
    private final String collaboratorPassword;
    private final boolean secureCookie;
    private final boolean accountSecureCookie;

    public PublicDeploymentSafetyValidator(
            Environment environment,
            @Value("${deployment.mode:local}") String deploymentMode,
            @Value("${security.public.api-key:}") String publicApiKey,
            @Value("${security.public.collaborator.password:}") String collaboratorPassword,
            @Value("${security.public.session.secure-cookie:false}") boolean secureCookie,
            @Value("${account.auth.secure-cookie:false}") boolean accountSecureCookie) {
        this.environment = environment;
        this.deploymentMode = deploymentMode;
        this.publicApiKey = publicApiKey;
        this.collaboratorPassword = collaboratorPassword;
        this.secureCookie = secureCookie;
        this.accountSecureCookie = accountSecureCookie;
    }

    @PostConstruct
    public void validateOrFail() {
        validate(
                environment.getActiveProfiles(),
                deploymentMode,
                publicApiKey,
                collaboratorPassword,
                secureCookie,
                accountSecureCookie);
        if (isProdLike(environment.getActiveProfiles())) {
            log.info("Production public-mode safety checks passed (auth gate + Secure cookies)");
        }
    }

    static void validate(
            String[] activeProfiles,
            String deploymentMode,
            String publicApiKey,
            String collaboratorPassword,
            boolean secureCookie,
            boolean accountSecureCookie) {
        if (!isProdLike(activeProfiles)) {
            return;
        }
        if (!DeploymentMode.isPublic(deploymentMode)) {
            throw new IllegalStateException(
                    "production profile requires deployment.mode=public so student-data and "
                            + "generation paths stay gated (BL-043.1 / SECURITY_AUDIT C-01)");
        }
        if (!secureCookie) {
            throw new IllegalStateException(
                    "production profile requires security.public.session.secure-cookie=true "
                            + "(BL-043.12 / SECURITY_AUDIT H-07)");
        }
        if (!accountSecureCookie) {
            throw new IllegalStateException(
                    "production profile requires account.auth.secure-cookie=true "
                            + "(BL-043.12 / SECURITY_AUDIT H-07)");
        }
        boolean apiKeyConfigured = !isBlank(publicApiKey);
        boolean collaboratorConfigured = !isBlank(collaboratorPassword);
        if (!apiKeyConfigured && !collaboratorConfigured) {
            throw new IllegalStateException(
                    "production public mode requires PUBLIC_API_KEY or PUBLIC_COLLABORATOR_PASSWORD "
                            + "so the auth gate cannot fail open (BL-043.1 / SECURITY_AUDIT C-01)");
        }
    }

    static boolean isProdLike(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        for (String profile : activeProfiles) {
            String normalized = profile == null ? "" : profile.toLowerCase(Locale.ROOT);
            if (normalized.contains("prod") || normalized.equals("mariadb")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
