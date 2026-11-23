package top.sywyar.pixivdownload.web;

import top.sywyar.pixivdownload.common.PixivConnectivityProbe;
import top.sywyar.pixivdownload.common.ServerStateProvider;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.config.RuntimeConfigReloadService;
import top.sywyar.pixivdownload.config.http.ServerAddressProvider;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.gui.bootstrap.ApplicationRestartService;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

class SystemManagementControllerTest {
    private final ServerStateProvider state = mock(ServerStateProvider.class);
    private final RuntimeConfigReloadService reload = mock(RuntimeConfigReloadService.class);
    private final ApplicationRestartService restart = mock(ApplicationRestartService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc;
    SystemManagementControllerTest() {
        var messages = TestI18nBeans.appMessages();
        mvc = MockMvcBuilders.standaloneSetup(new SystemManagementController(state, mock(ServerAddressProvider.class),
                reload, restart, mock(PixivConnectivityProbe.class), messages,
                new ToolApiRequests(messages, mock(WebI18nService.class)), mock(ApplicationContext.class))).build();
    }

    @Test void passwordValidationAndStorageFailurePreserveSafeDistinctResults() throws Exception {
        mvc.perform(post("/api/admin/system/password").contentType("application/json")
                .content("{\"oldPassword\":\"old-value\",\"newPassword\":\"tiny\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        verifyNoInteractions(state);
        String body = "{\"oldPassword\":\"old-value\",\"newPassword\":\"new-value\"}";
        doThrow(new IllegalArgumentException()).when(state).changePassword("old-value", "new-value");
        mvc.perform(post("/api/admin/system/password").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        doThrow(new IOException("private-secret")).when(state).changePassword("old-value", "new-value");
        mvc.perform(post("/api/admin/system/password").contentType("application/json").content(body))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("ACCOUNT_SAVE_FAILED"))
                .andExpect(content().string(not(containsString("private-secret"))));
        doNothing().when(state).changePassword("old-value", "new-value");
        mvc.perform(post("/api/admin/system/password").contentType("application/json").content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/system/logout-all")).andExpect(status().isNoContent());
        verify(state).revokeAllSessions();
    }

    @Test void reloadUsesOnlySavedKeysAndRestartReportsActualAcceptance() throws Exception {
        mvc.perform(post("/api/admin/system/reload").contentType("application/json").content("{\"changedKeys\":[],\"values\":{}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reload);
        when(reload.reloadHotConfig(List.of())).thenReturn(new RuntimeConfigReloadService.ReloadResult(List.of()));
        mvc.perform(post("/api/admin/system/reload").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/system/restart")).andExpect(status().isServiceUnavailable());
        when(restart.requestRestart()).thenReturn(true);
        mvc.perform(post("/api/admin/system/restart")).andExpect(status().isAccepted());
    }
}
