package com.classicchatreader.controller;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.service.ReadingBuddyPersonaCatalog;
import com.classicchatreader.service.llm.LlmProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading Buddy REST surface. PR1 exposes status and canned personas only
 * (no chat, preferences, or proactive comment endpoints yet).
 */
@RestController
@RequestMapping("/api/reading-buddy")
public class ReadingBuddyController {

    private final ReadingBuddyProperties properties;
    private final ReadingBuddyPersonaCatalog personaCatalog;
    private final LlmProvider chatProvider;

    @Value("${ai.chat.enabled:false}")
    private boolean chatEnabled;

    public ReadingBuddyController(
            ReadingBuddyProperties properties,
            ReadingBuddyPersonaCatalog personaCatalog,
            @Qualifier("chatLlmProvider") LlmProvider chatProvider) {
        this.properties = properties;
        this.personaCatalog = personaCatalog;
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

    private Map<String, Object> toPublicPersona(ReadingBuddyPersona persona) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", persona.id());
        dto.put("displayName", persona.displayName());
        dto.put("shortBlurb", persona.shortBlurb());
        dto.put("toneTags", persona.toneTags());
        dto.put("portraitUrl", persona.portraitPath());
        return dto;
    }
}
