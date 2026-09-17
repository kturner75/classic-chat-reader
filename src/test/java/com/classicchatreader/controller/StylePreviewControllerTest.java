package com.classicchatreader.controller;

import com.classicchatreader.service.StylePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StylePreviewControllerTest {
    @Test
    void cacheOnlyDisablesCapabilityAndGeneration() throws Exception {
        var service = mock(StylePreviewService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new StylePreviewController(service, true)).build();
        mvc.perform(get("/api/style-previews")).andExpect(jsonPath("$.available").value(false));
        mvc.perform(post("/api/style-previews").contentType("application/json").content("{\"family\":\"cover\",\"prompt\":\"Ink\"}")).andExpect(status().isConflict());
        verifyNoInteractions(service);
    }

    @Test
    void returnsUncachedBytesAndRejectsInvalidFamily() throws Exception {
        var service = mock(StylePreviewService.class);
        when(service.generate("cover", "Ink")).thenReturn(new byte[]{1, 2});
        when(service.generate("other", "Ink")).thenThrow(new IllegalArgumentException("Unknown family"));
        var mvc = MockMvcBuilders.standaloneSetup(new StylePreviewController(service, false)).build();
        mvc.perform(post("/api/style-previews").contentType("application/json").content("{\"family\":\"cover\",\"prompt\":\"Ink\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(content().bytes(new byte[]{1, 2}));
        mvc.perform(post("/api/style-previews").contentType("application/json").content("{\"family\":\"other\",\"prompt\":\"Ink\"}")).andExpect(status().isBadRequest());
    }
}
