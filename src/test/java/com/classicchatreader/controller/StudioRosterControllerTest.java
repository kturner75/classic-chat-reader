package com.classicchatreader.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudioRosterControllerTest {
    @Test void remoteOrForwardedRequestsCannotReachDatabase() throws Exception {
        DataSource ds = mock(DataSource.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StudioRosterController(ds)).build();
        mvc.perform(get("/api/studio/roster/gutenberg/17396").with(r -> { r.setRemoteAddr("192.0.2.1"); return r; })).andExpect(status().isForbidden());
        mvc.perform(get("/api/studio/roster/gutenberg/17396").header("X-Forwarded-For","192.0.2.1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/studio/roster/gutenberg/17396").header("Origin","https://other.example")).andExpect(status().isForbidden());
        mvc.perform(get("http://other.example/api/studio/roster/gutenberg/17396")).andExpect(status().isForbidden());
        verifyNoInteractions(ds);
    }
    @Test void replacementRejectsRoutePlanMismatchBeforeDatabase() throws Exception {
        DataSource ds = mock(DataSource.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StudioRosterController(ds)).build();
        mvc.perform(post("/api/studio/roster/gutenberg/17396/replace").contentType("application/json")
            .content("{\"source\":\"gutenberg\",\"sourceId\":\"84\",\"confirm\":true}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(ds);
    }
}
