package com.aava.datapowerai.controller;

import com.aava.datapowerai.service.AppVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Story 104470: App Version Management / Version History – Backend
 *
 * Focused MVC tests for endpoint wiring + security (service is mocked).
 */
// ASSUMPTION: not found in repo content
@WebMvcTest(controllers = AppVersionController.class)
class AppVersionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AppVersionService appVersionService;

    @Test
    void unauthenticated_listVersions_is401() throws Exception {
        mvc.perform(get("/api/apps/app-1/versions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user")
    void authenticated_listVersions_is200() throws Exception {
        when(appVersionService.listVersions(anyString(), any()))
                .thenReturn(new com.aava.datapowerai.dto.app.AppVersionDTOs.AppVersionsResponse("app-1", java.util.List.of()));

        mvc.perform(get("/api/apps/app-1/versions"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void unauthenticated_restore_is401() throws Exception {
        mvc.perform(post("/api/apps/app-1/versions/1/restore"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"TOOL_ADMIN"})
    void authorized_restore_is200() throws Exception {
        when(appVersionService.restoreVersion(eq("app-1"), eq(1), any()))
                .thenReturn(new com.aava.datapowerai.dto.app.AppVersionDTOs.RestoreResponse("app-1", 1, true));

        mvc.perform(post("/api/apps/app-1/versions/1/restore"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true));
    }
}
