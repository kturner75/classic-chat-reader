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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReadingBuddyController.class)
@Import({ReadingBuddyProperties.class, ReadingBuddyPersonaCatalog.class})
@TestPropertySource(properties = {
        "reading-buddy.enabled=true",
        "ai.chat.enabled=false"
})
class ReadingBuddyControllerChatDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "chatLlmProvider")
    private LlmProvider chatLlmProvider;

    @MockitoBean
    private ReadingBuddyPreferenceService preferenceService;

    @MockitoBean
    private ReaderIdentityService readerIdentityService;

    @Test
    void status_whenChatDisabled_availableIsFalseEvenIfBuddyAndProviderReady() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/reading-buddy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(false)))
                .andExpect(jsonPath("$.providerAvailable", is(true)))
                .andExpect(jsonPath("$.available", is(false)));
    }
}
