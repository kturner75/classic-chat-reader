package com.classicchatreader.controller;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.service.ReaderIdentityService;
import com.classicchatreader.service.ReadingBuddyPersonaCatalog;
import com.classicchatreader.service.ReadingBuddyPreferenceService;
import com.classicchatreader.service.llm.LlmProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Reading Buddy REST surface. PR2 adds preferences GET/PUT on top of status + personas.
 */
@RestController
@RequestMapping("/api/reading-buddy")
public class ReadingBuddyController {

    private final ReadingBuddyProperties properties;
    private final ReadingBuddyPersonaCatalog personaCatalog;
    private final ReadingBuddyPreferenceService preferenceService;
    private final ReaderIdentityService readerIdentityService;
    private final LlmProvider chatProvider;

    @Value("${ai.chat.enabled:false}")
    private boolean chatEnabled;

    public ReadingBuddyController(
            ReadingBuddyProperties properties,
            ReadingBuddyPersonaCatalog personaCatalog,
            ReadingBuddyPreferenceService preferenceService,
            ReaderIdentityService readerIdentityService,
            @Qualifier("chatLlmProvider") LlmProvider chatProvider) {
        this.properties = properties;
        this.personaCatalog = personaCatalog;
        this.preferenceService = preferenceService;
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
}
