package com.classicchatreader.controller;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.service.ReaderIdentityService;
import com.classicchatreader.service.ReadingBuddyPersonaCatalog;
import com.classicchatreader.service.ReadingBuddyPreferenceService;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReadingBuddyController.class)
@Import({ReadingBuddyProperties.class, ReadingBuddyPersonaCatalog.class})
@TestPropertySource(properties = {
        "reading-buddy.enabled=true",
        "ai.chat.enabled=true",
        "reading-buddy.proactive.max-words=60",
        "reading-buddy.chat.max-words=150"
})
class ReadingBuddyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "chatLlmProvider")
    private LlmProvider chatLlmProvider;

    @MockitoBean
    private ReadingBuddyPreferenceService preferenceService;

    @MockitoBean
    private ReaderIdentityService readerIdentityService;

    @Test
    void status_whenAllGatesOpen_availableIsTrue() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/reading-buddy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(true)))
                .andExpect(jsonPath("$.providerAvailable", is(true)))
                .andExpect(jsonPath("$.available", is(true)));
    }

    @Test
    void status_whenProviderUnavailable_availableIsFalse() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/reading-buddy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(true)))
                .andExpect(jsonPath("$.providerAvailable", is(false)))
                .andExpect(jsonPath("$.available", is(false)));
    }

    @Test
    void personas_returnsPublicCatalogWithoutSystemPrompts() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/reading-buddy/personas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].id", is("historian")))
                .andExpect(jsonPath("$[0].displayName", is("The Archivist")))
                .andExpect(jsonPath("$[0].shortBlurb").isNotEmpty())
                .andExpect(jsonPath("$[0].toneTags", hasSize(2)))
                .andExpect(jsonPath("$[0].portraitUrl", is("/images/buddies/historian.png")))
                // Public DTO is an explicit allow-list of five keys only.
                .andExpect(jsonPath("$[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$[0].temperature").doesNotExist())
                .andExpect(jsonPath("$[0].maxProactiveWords").doesNotExist())
                .andExpect(jsonPath("$[0].maxChatWords").doesNotExist())
                .andExpect(jsonPath("$[1].id", is("close_reader")))
                .andExpect(jsonPath("$[2].id", is("humorist")))
                .andExpect(jsonPath("$[3].id", is("encourager")))
                .andExpect(jsonPath("$[3].displayName", is("The Steady Companion")))
                .andExpect(jsonPath("$[3].portraitUrl", is("/images/buddies/encourager.png")));
    }

    @Test
    void preferences_get_returnsEffectiveFromIdentity() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(preferenceService.getEffective(eq("reader-1"), isNull()))
                .thenReturn(new ReadingBuddyPreferenceService.EffectivePreferences(
                        false,
                        "rare",
                        "close_reader",
                        "close_reader",
                        "global",
                        null,
                        null
                ));

        mockMvc.perform(get("/api/reading-buddy/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)))
                .andExpect(jsonPath("$.frequency", is("rare")))
                .andExpect(jsonPath("$.defaultPersonaId", is("close_reader")))
                .andExpect(jsonPath("$.personaId", is("close_reader")))
                .andExpect(jsonPath("$.personaSource", is("global")))
                .andExpect(jsonPath("$.suppressUntilEpochMs", nullValue()))
                .andExpect(jsonPath("$.bookId", nullValue()));
    }

    @Test
    void preferences_put_appliesPartialUpdate() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("user:u1", true, "u1"));
        when(preferenceService.update(eq("user:u1"), any()))
                .thenReturn(new ReadingBuddyPreferenceService.EffectivePreferences(
                        true,
                        "occasional",
                        "historian",
                        "humorist",
                        "book_override",
                        null,
                        "book-1"
                ));

        mockMvc.perform(put("/api/reading-buddy/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "frequency": "occasional",
                                  "personaId": "humorist",
                                  "bookId": "book-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.frequency", is("occasional")))
                .andExpect(jsonPath("$.personaId", is("humorist")))
                .andExpect(jsonPath("$.personaSource", is("book_override")))
                .andExpect(jsonPath("$.bookId", is("book-1")));
    }

    @Test
    void preferences_put_unknownPersona_returns400() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(preferenceService.update(eq("reader-1"), any()))
                .thenThrow(new IllegalArgumentException("Unknown personaId: nope"));

        mockMvc.perform(put("/api/reading-buddy/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultPersonaId\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_PREFERENCES")));
    }
}
