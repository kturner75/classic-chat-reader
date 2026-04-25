package com.classicchatreader.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderIdentityServiceTest {

    @Mock
    private AccountAuthService accountAuthService;

    @Mock
    private ReaderProfileService readerProfileService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private ReaderIdentityService readerIdentityService;

    @BeforeEach
    void setUp() {
        readerIdentityService = new ReaderIdentityService(accountAuthService, readerProfileService);
    }

    @Test
    void resolve_authenticatedAccount_usesUserScopedReaderKey() {
        when(accountAuthService.resolveAuthenticatedPrincipal(any()))
                .thenReturn(Optional.of(new AccountAuthService.AccountPrincipal("user-123", "reader@example.com")));

        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);

        assertEquals("user:user-123", identity.readerKey());
        assertTrue(identity.accountAuthenticated());
        assertEquals("user-123", identity.userId());
        verifyNoInteractions(readerProfileService);
    }

    @Test
    void resolve_anonymousFallsBackToReaderCookieId() {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.empty());
        when(readerProfileService.resolveReaderId(any(), any())).thenReturn("reader-cookie-abc");

        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);

        assertEquals("reader-cookie-abc", identity.readerKey());
        assertFalse(identity.accountAuthenticated());
        assertNull(identity.userId());
        verify(readerProfileService).resolveReaderId(request, response);
    }
}
