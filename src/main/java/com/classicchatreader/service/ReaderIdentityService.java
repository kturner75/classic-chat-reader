package com.classicchatreader.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ReaderIdentityService {

    private final AccountAuthService accountAuthService;
    private final ReaderProfileService readerProfileService;

    public ReaderIdentityService(
            AccountAuthService accountAuthService,
            ReaderProfileService readerProfileService) {
        this.accountAuthService = accountAuthService;
        this.readerProfileService = readerProfileService;
    }

    public ReaderIdentity resolve(HttpServletRequest request, HttpServletResponse response) {
        Optional<AccountAuthService.AccountPrincipal> principal =
                accountAuthService.resolveAuthenticatedPrincipal(request);
        if (principal.isPresent()) {
            String userId = principal.get().userId();
            return new ReaderIdentity("user:" + userId, true, userId);
        }

        String readerId = readerProfileService.resolveReaderId(request, response);
        return new ReaderIdentity(readerId, false, null);
    }

    public record ReaderIdentity(
            String readerKey,
            boolean accountAuthenticated,
            String userId
    ) {
    }
}
