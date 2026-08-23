package com.classicchatreader.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicDeploymentSafetyValidatorTest {

    @Test
    void localProfile_doesNotRequirePublicModeOrAuthMaterial() {
        assertDoesNotThrow(() -> PublicDeploymentSafetyValidator.validate(
                new String[]{"local-dev"},
                "local",
                "",
                "",
                false,
                false));
    }

    @Test
    void publicModeTests_withoutProdProfile_doNotFailClosed() {
        assertDoesNotThrow(() -> PublicDeploymentSafetyValidator.validate(
                new String[]{},
                "public",
                "",
                "",
                false,
                false));
    }

    @Test
    void prodProfile_localMode_refusesToBoot() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PublicDeploymentSafetyValidator.validate(
                        new String[]{"prod"},
                        "local",
                        "api-key",
                        "",
                        true,
                        true));
        assertTrue(error.getMessage().contains("deployment.mode=public"));
    }

    @Test
    void prodProfile_insecureSessionCookies_refusesToBoot() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PublicDeploymentSafetyValidator.validate(
                        new String[]{"prod"},
                        "public",
                        "api-key",
                        "",
                        false,
                        true));
        assertTrue(error.getMessage().contains("security.public.session.secure-cookie=true"));
    }

    @Test
    void prodProfile_insecureAccountCookies_refusesToBoot() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PublicDeploymentSafetyValidator.validate(
                        new String[]{"prod"},
                        "public",
                        "api-key",
                        "",
                        true,
                        false));
        assertTrue(error.getMessage().contains("account.auth.secure-cookie=true"));
    }

    @Test
    void prodProfile_withoutAuthMaterial_refusesToBoot() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PublicDeploymentSafetyValidator.validate(
                        new String[]{"prod"},
                        "public",
                        "  ",
                        "",
                        true,
                        true));
        assertTrue(error.getMessage().contains("PUBLIC_API_KEY"));
    }

    @Test
    void prodProfile_withCollaboratorPasswordOnly_boots() {
        assertDoesNotThrow(() -> PublicDeploymentSafetyValidator.validate(
                new String[]{"prod"},
                "public",
                "",
                "collaborator-password",
                true,
                true));
    }

    @Test
    void prodProfile_paddedPublicMode_isStillPublic() {
        assertDoesNotThrow(() -> PublicDeploymentSafetyValidator.validate(
                new String[]{"prod"},
                " public \r",
                "api-key",
                "",
                true,
                true));
    }

    @Test
    void mariadbProfile_isTreatedAsProduction() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                PublicDeploymentSafetyValidator.validate(
                        new String[]{"mariadb"},
                        "local",
                        "",
                        "",
                        false,
                        false));
        assertTrue(error.getMessage().contains("deployment.mode=public"));
    }

    @Test
    void prodProperties_pinPublicModeAndSecureCookies() throws Exception {
        Properties properties = loadClasspathProperties("/application-prod.properties");
        assertEquals("public", properties.getProperty("deployment.mode"));
        assertEquals("true", properties.getProperty("security.public.session.secure-cookie"));
        assertEquals("true", properties.getProperty("account.auth.secure-cookie"));
    }

    @Test
    void mariadbProperties_pinPublicModeAndSecureCookies() throws Exception {
        Properties properties = loadClasspathProperties("/application-mariadb.properties");
        assertEquals("public", properties.getProperty("deployment.mode"));
        assertEquals("true", properties.getProperty("security.public.session.secure-cookie"));
        assertEquals("true", properties.getProperty("account.auth.secure-cookie"));
    }

    private static Properties loadClasspathProperties(String path) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = PublicDeploymentSafetyValidatorTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            properties.load(in);
        }
        return properties;
    }
}
