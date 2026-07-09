package com.classicchatreader.controller;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.service.ReaderIdentityService;
import com.classicchatreader.service.ReadingBuddyChatService;
import com.classicchatreader.service.ReadingBuddyMemoryService;
import com.classicchatreader.service.ReadingBuddyMetricsService;
import com.classicchatreader.service.ReadingBuddyPersonaCatalog;
import com.classicchatreader.service.ReadingBuddyPreferenceService;
import com.classicchatreader.service.llm.LlmProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading Buddy REST surface: status, personas, preferences, chat, and history.
 */
@RestController
@RequestMapping("/api/reading-buddy")
public class ReadingBuddyController {

    private final ReadingBuddyProperties properties;
    private final ReadingBuddyPersonaCatalog personaCatalog;
    private final ReadingBuddyPreferenceService preferenceService;
    private final ReadingBuddyChatService chatService;
    private final ReadingBuddyMemoryService memoryService;
    private final ReadingBuddyMetricsService metricsService;
    private final ReaderIdentityService readerIdentityService;
    private final LlmProvider chatProvider;

    @Value("${ai.chat.enabled:false}")
    private boolean chatEnabled;

    public ReadingBuddyController(
            ReadingBuddyProperties properties,
            ReadingBuddyPersonaCatalog personaCatalog,
            ReadingBuddyPreferenceService preferenceService,
            ReadingBuddyChatService chatService,
            ReadingBuddyMemoryService memoryService,
            ReadingBuddyMetricsService metricsService,
            ReaderIdentityService readerIdentityService,
            @Qualifier("chatLlmProvider") LlmProvider chatProvider) {
        this.properties = properties;
        this.personaCatalog = personaCatalog;
        this.preferenceService = preferenceService;
        this.chatService = chatService;
        this.memoryService = memoryService;
        this.metricsService = metricsService;
        this.readerIdentityService = readerIdentityService;
        this.chatProvider = chatProvider;
    }

    /**
     * Feature availability for the frontend. Source of truth for buddy enablement —
     * not dual-published on {@link FeatureController}.
     *
     * <p>{@code available === enabled && chatEnabled && providerAvailable}
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        boolean enabled = properties.isEnabled();
        boolean providerAvailable = chatProvider.isAvailable();
        boolean available = enabled && chatEnabled && providerAvailable;

        Map<String, Object> status = new HashMap<>();
        status.put("enabled", enabled);
        status.put("chatEnabled", chatEnabled);
        status.put("providerAvailable", providerAvailable);
        status.put("available", available);
        return status;
    }

    /**
     * Public catalog of canned personas (id, display name, blurb, tone tags, portrait URL).
     * System prompts and generation knobs stay server-side.
     */
    @GetMapping("/personas")
    public List<Map<String, Object>> listPersonas() {
        return personaCatalog.listAll().stream()
                .map(this::toPublicPersona)
                .toList();
    }

    /**
     * Effective preferences for the resolved reader identity.
     * Optional {@code bookId} applies book-level persona override when present.
     */
    @GetMapping("/preferences")
    public Map<String, Object> getPreferences(
            @RequestParam(value = "bookId", required = false) String bookId,
            HttpServletRequest request,
            HttpServletResponse response) {
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        ReadingBuddyPreferenceService.EffectivePreferences prefs =
                preferenceService.getEffective(identity.readerKey(), bookId);
        return toPreferencesDto(prefs);
    }

    /**
     * Partial preference update for the resolved reader identity.
     */
    @PutMapping("/preferences")
    public ResponseEntity<?> putPreferences(
            @RequestBody(required = false) PreferenceRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        try {
            ReadingBuddyPreferenceService.PreferenceUpdate update = toUpdate(body);
            ReadingBuddyPreferenceService.EffectivePreferences prefs =
                    preferenceService.update(identity.readerKey(), update);
            return ResponseEntity.ok(toPreferencesDto(prefs));
        } catch (ReadingBuddyPreferenceService.BookNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(errorBody("BOOK_NOT_FOUND", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(errorBody("INVALID_PREFERENCES", e.getMessage()));
        }
    }

    /**
     * Interactive chat. Server loads recent DB messages (position-filtered) for prompts;
     * any client {@code conversationHistory} is ignored.
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @RequestBody(required = false) ChatRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!isFeatureAndChatEnabled()) {
            metricsService.recordChatRejected();
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(errorBody("CHAT_DISABLED", "Reading buddy chat is disabled in this environment."));
        }

        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        if (body == null) {
            metricsService.recordChatRejected();
            return ResponseEntity.badRequest()
                    .body(errorBody("INVALID_REQUEST", "Request body is required"));
        }

        try {
            ReadingBuddyChatService.ChatResult result = chatService.chat(
                    identity.readerKey(),
                    body.bookId(),
                    body.personaId(),
                    body.message(),
                    body.readerChapterIndex(),
                    body.readerParagraphIndex()
            );
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("response", result.response());
            dto.put("personaId", result.personaId());
            dto.put("messageId", result.messageId());
            dto.put("userMessageId", result.userMessageId());
            dto.put("timestamp", result.timestamp());
            return ResponseEntity.ok(dto);
        } catch (ReadingBuddyChatService.BookNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(errorBody("BOOK_NOT_FOUND", e.getMessage()));
        } catch (ReadingBuddyChatService.ValidationException e) {
            metricsService.recordChatRejected();
            return ResponseEntity.badRequest()
                    .body(errorBody(e.getErrorCode(), e.getMessage()));
        }
    }

    /**
     * Chronological history for owner×book×persona with rewind visibility flags.
     */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam("bookId") String bookId,
            @RequestParam("personaId") String personaId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam("readerChapterIndex") int readerChapterIndex,
            @RequestParam("readerParagraphIndex") int readerParagraphIndex,
            @RequestParam(value = "includeHidden", defaultValue = "true") boolean includeHidden,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!isFeatureAndChatEnabled()) {
            metricsService.recordChatRejected();
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(errorBody("CHAT_DISABLED", "Reading buddy chat is disabled in this environment."));
        }
        if (bookId == null || bookId.isBlank()) {
            metricsService.recordChatRejected();
            return ResponseEntity.badRequest()
                    .body(errorBody("INVALID_BOOK_ID", "bookId is required"));
        }
        if (personaId == null || personaId.isBlank() || !personaCatalog.isKnown(personaId.trim())) {
            metricsService.recordChatRejected();
            return ResponseEntity.badRequest()
                    .body(errorBody("UNKNOWN_PERSONA", "Unknown personaId: " + personaId));
        }
        if (readerChapterIndex < 0 || readerParagraphIndex < 0) {
            metricsService.recordChatRejected();
            return ResponseEntity.badRequest()
                    .body(errorBody("INVALID_POSITION",
                            "readerChapterIndex and readerParagraphIndex must be non-negative"));
        }

        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        ReadingBuddyMemoryService.HistoryResult history = memoryService.getHistory(
                identity.readerKey(),
                bookId.trim(),
                personaId.trim(),
                limit,
                readerChapterIndex,
                readerParagraphIndex,
                includeHidden);

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("personaId", history.personaId());
        dto.put("bookId", history.bookId());
        dto.put("messages", history.messages().stream().map(this::toHistoryMessageDto).toList());
        return ResponseEntity.ok(dto);
    }

    /**
     * Clears messages and empties memory summary/watermarks for owner×book×persona.
     */
    @DeleteMapping("/history")
    public ResponseEntity<?> deleteHistory(
            @RequestParam("bookId") String bookId,
            @RequestParam("personaId") String personaId,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!isFeatureAndChatEnabled()) {
            metricsService.recordChatRejected();
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(errorBody("CHAT_DISABLED", "Reading buddy chat is disabled in this environment."));
        }
        if (bookId == null || bookId.isBlank()) {
            metricsService.recordChatRejected();
            return ResponseEntity.badRequest()
                    .body(errorBody("INVALID_BOOK_ID", "bookId is required"));
        }
        if (personaId == null || personaId.isBlank() || !personaCatalog.isKnown(personaId.trim())) {
            metricsService.recordChatRejected();
            return ResponseEntity.badRequest()
                    .body(errorBody("UNKNOWN_PERSONA", "Unknown personaId: " + personaId));
        }

        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        memoryService.clearHistory(identity.readerKey(), bookId.trim(), personaId.trim());

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("cleared", true);
        dto.put("bookId", bookId.trim());
        dto.put("personaId", personaId.trim());
        return ResponseEntity.ok(dto);
    }

    private boolean isFeatureAndChatEnabled() {
        return properties.isEnabled() && chatEnabled;
    }

    private ReadingBuddyPreferenceService.PreferenceUpdate toUpdate(PreferenceRequest body) {
        if (body == null) {
            return new ReadingBuddyPreferenceService.PreferenceUpdate(
                    null, null, null, null, null, null, null, null
            );
        }
        return new ReadingBuddyPreferenceService.PreferenceUpdate(
                body.enabled(),
                body.frequency(),
                body.defaultPersonaId(),
                body.personaId(),
                body.bookId(),
                body.clearBookPersona(),
                body.suppressUntilEpochMs(),
                body.quietMinutes()
        );
    }

    private Map<String, Object> toPreferencesDto(ReadingBuddyPreferenceService.EffectivePreferences prefs) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("enabled", prefs.enabled());
        dto.put("frequency", prefs.frequency());
        dto.put("defaultPersonaId", prefs.defaultPersonaId());
        dto.put("personaId", prefs.personaId());
        dto.put("personaSource", prefs.personaSource());
        dto.put("suppressUntilEpochMs", prefs.suppressUntilEpochMs());
        dto.put("bookId", prefs.bookId());
        return dto;
    }

    private Map<String, Object> toHistoryMessageDto(ReadingBuddyMemoryService.HistoryMessage msg) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", msg.id());
        dto.put("role", msg.role());
        dto.put("content", msg.content());
        dto.put("kind", msg.kind());
        dto.put("chapterIndex", msg.chapterIndex());
        dto.put("paragraphIndex", msg.paragraphIndex());
        dto.put("createdAt", msg.createdAt());
        dto.put("visibleAtPosition", msg.visibleAtPosition());
        return dto;
    }

    private Map<String, Object> toPublicPersona(ReadingBuddyPersona persona) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", persona.id());
        dto.put("displayName", persona.displayName());
        dto.put("shortBlurb", persona.shortBlurb());
        dto.put("toneTags", persona.toneTags());
        dto.put("portraitUrl", persona.portraitPath());
        return dto;
    }

    private static Map<String, String> errorBody(String error, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }

    public record PreferenceRequest(
            Boolean enabled,
            String frequency,
            String defaultPersonaId,
            String personaId,
            String bookId,
            Boolean clearBookPersona,
            Long suppressUntilEpochMs,
            Integer quietMinutes
    ) {
    }

    /**
     * Chat request. {@code conversationHistory} may be present for client compat but is ignored.
     */
    public record ChatRequest(
            String bookId,
            String personaId,
            String message,
            int readerChapterIndex,
            int readerParagraphIndex,
            List<Map<String, Object>> conversationHistory
    ) {
    }
}
