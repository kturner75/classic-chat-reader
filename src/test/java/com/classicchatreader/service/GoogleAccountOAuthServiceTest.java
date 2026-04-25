package com.classicchatreader.service;

import jakarta.servlet.http.Cookie;
import com.classicchatreader.config.GoogleAccountOAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleAccountOAuthServiceTest {

    @Mock
    private AccountAuthService accountAuthService;

    @Mock
    private JwtDecoder jwtDecoder;

    private GoogleAccountOAuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GoogleAccountOAuthProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setRedirectUri("https://reader.example.com/api/account/google/callback");
    }

    @Test
    void completeAuthorization_validFlowSignsInAndRedirectsBackToReturnUrl() {
        GoogleAccountOAuthService service = new GoogleAccountOAuthService(
                accountAuthService,
                properties,
                buildWebClient("""
                        {
                          "access_token":"access-token",
                          "id_token":"id-token",
                          "token_type":"Bearer",
                          "expires_in":3600,
                          "scope":"openid email profile"
                        }
                        """),
                jwtDecoder,
                false
        );

        MockHttpServletResponse startResponse = new MockHttpServletResponse();
        GoogleAccountOAuthService.AuthorizationStartResult start =
                service.beginAuthorization("/reader?book=1#p2", startResponse);
        String returnedState = UriComponentsBuilder.fromUri(start.redirectUri())
                .build()
                .getQueryParams()
                .getFirst("state");
        String nonce = findCookieValue(startResponse, "pdr_account_google_nonce");

        when(jwtDecoder.decode("id-token")).thenReturn(validJwt(nonce));
        when(accountAuthService.signInWithExternalIdentity(any(), any()))
                .thenReturn(AccountAuthService.AuthResult.success(true, "reader@example.com", "Signed in."));

        MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
        callbackRequest.setCookies(extractCookies(startResponse));
        MockHttpServletResponse callbackResponse = new MockHttpServletResponse();

        GoogleAccountOAuthService.AuthorizationCallbackResult result =
                service.completeAuthorization("auth-code", returnedState, null, callbackRequest, callbackResponse);

        assertTrue(result.authenticated());
        assertEquals("/reader?book=1&account_notice=google_signed_in#p2", result.redirectUri().toString());

        ArgumentCaptor<AccountAuthService.ExternalIdentity> identityCaptor =
                ArgumentCaptor.forClass(AccountAuthService.ExternalIdentity.class);
        verify(accountAuthService).signInWithExternalIdentity(identityCaptor.capture(), eq(callbackResponse));
        assertEquals("google", identityCaptor.getValue().provider());
        assertEquals("google-subject", identityCaptor.getValue().providerSubject());
        assertEquals("reader@example.com", identityCaptor.getValue().email());
        assertTrue(identityCaptor.getValue().emailVerified());
    }

    @Test
    void completeAuthorization_stateMismatchRedirectsWithFailureNotice() {
        GoogleAccountOAuthService service = new GoogleAccountOAuthService(
                accountAuthService,
                properties,
                buildWebClient("""
                        {"access_token":"unused","id_token":"unused","token_type":"Bearer","expires_in":3600}
                        """),
                jwtDecoder,
                false
        );

        MockHttpServletResponse startResponse = new MockHttpServletResponse();
        service.beginAuthorization("/reader", startResponse);

        MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
        callbackRequest.setCookies(extractCookies(startResponse));
        MockHttpServletResponse callbackResponse = new MockHttpServletResponse();

        GoogleAccountOAuthService.AuthorizationCallbackResult result =
                service.completeAuthorization("auth-code", "wrong-state", null, callbackRequest, callbackResponse);

        assertFalse(result.authenticated());
        assertEquals("/reader?account_notice=google_state_mismatch", result.redirectUri().toString());
        assertEquals("state_mismatch", result.failureReason());
    }

    private WebClient buildWebClient(String jsonBody) {
        ExchangeFunction exchangeFunction = request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(jsonBody)
                        .build()
        );
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private Jwt validJwt(String nonce) {
        Instant now = Instant.now();
        return new Jwt(
                "id-token",
                now.minusSeconds(60),
                now.plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "google-subject",
                        "email", "reader@example.com",
                        "email_verified", true,
                        "nonce", nonce,
                        "iss", "https://accounts.google.com",
                        "aud", List.of("client-id")
                )
        );
    }

    private Cookie[] extractCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .map(header -> header.split(";", 2)[0])
                .map(cookiePair -> cookiePair.split("=", 2))
                .map(parts -> new Cookie(parts[0], parts.length > 1 ? parts[1] : ""))
                .toArray(Cookie[]::new);
    }

    private String findCookieValue(MockHttpServletResponse response, String cookieName) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .map(header -> header.split(";", 2)[0])
                .map(cookiePair -> cookiePair.split("=", 2))
                .filter(parts -> parts.length == 2 && cookieName.equals(parts[0]))
                .map(parts -> parts[1])
                .findFirst()
                .orElseThrow();
    }
}
