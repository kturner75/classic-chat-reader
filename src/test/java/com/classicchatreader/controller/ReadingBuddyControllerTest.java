package com.classicchatreader.controller;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.service.ReadingBuddyPersonaCatalog;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$[1].id", is("close_reader")))
                .andExpect(jsonPath("$[2].id", is("humorist")))
                .andExpect(jsonPath("$[3].id", is("encourager")))
                .andExpect(jsonPath("$[3].displayName", is("The Steady Companion")))
                .andExpect(jsonPath("$[3].portraitUrl", is("/images/buddies/encourager.png")));
    }
}
