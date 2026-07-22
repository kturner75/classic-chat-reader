package com.classicchatreader.controller;

import com.classicchatreader.model.AccountChatModels.ApiError;
import com.classicchatreader.model.AccountChatModels.ContinueRequest;
import com.classicchatreader.model.AccountChatModels.ContinueResponse;
import com.classicchatreader.model.AccountChatModels.ErrorEnvelope;
import com.classicchatreader.model.AccountChatModels.FilterOptionsResponse;
import com.classicchatreader.model.AccountChatModels.SessionDetailResponse;
import com.classicchatreader.model.AccountChatModels.SessionListResponse;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.AccountChatHistoryService;
import com.classicchatreader.service.ChatHistoryValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account/chats")
public class AccountChatController {

    private static final String PRIVATE_NO_STORE = "private, no-store";

    private final AccountAuthService accountAuthService;
    private final AccountChatHistoryService chatHistoryService;

    public AccountChatController(
            AccountAuthService accountAuthService,
            AccountChatHistoryService chatHistoryService) {
        this.accountAuthService = accountAuthService;
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String characterId,
            @RequestParam(required = false) String activeAfter,
            @RequestParam(required = false) String activeBefore,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        var principal = accountAuthService.resolveAuthenticatedPrincipal(request);
        if (principal.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required.");
        try {
            SessionListResponse response = chatHistoryService.list(
                    principal.get().userId(),
                    new AccountChatHistoryService.ListRequest(
                            limit, cursor, q, bookId, characterId, activeAfter, activeBefore, sort
                    )
            );
            return ok(response);
        } catch (ChatHistoryValidationException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
        }
    }

    @GetMapping("/filters")
    public ResponseEntity<?> filters(HttpServletRequest request) {
        var principal = accountAuthService.resolveAuthenticatedPrincipal(request);
        if (principal.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required.");
        FilterOptionsResponse response = chatHistoryService.filterOptions(principal.get().userId());
        return ok(response);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> get(@PathVariable String sessionId, HttpServletRequest request) {
        var principal = accountAuthService.resolveAuthenticatedPrincipal(request);
        if (principal.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required.");
        SessionDetailResponse response = chatHistoryService.get(principal.get().userId(), sessionId);
        if (response == null) {
            return error(HttpStatus.NOT_FOUND, "CHAT_NOT_FOUND", "Chat session not found.");
        }
        return ok(response);
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<?> continueConversation(
            @PathVariable String sessionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ContinueRequest body,
            HttpServletRequest request) {
        var principal = accountAuthService.resolveAuthenticatedPrincipal(request);
        if (principal.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required.");
        try {
            ContinueResponse response = chatHistoryService.continueConversation(
                    principal.get().userId(), sessionId, body, idempotencyKey);
            if (response == null) {
                return error(HttpStatus.NOT_FOUND, "CHAT_NOT_FOUND", "Chat session not found.");
            }
            return ok(response);
        } catch (ChatHistoryValidationException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
        }
    }

    private ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
                .body(body);
    }

    private ResponseEntity<ErrorEnvelope> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
                .body(new ErrorEnvelope(new ApiError(code, message)));
    }
}
