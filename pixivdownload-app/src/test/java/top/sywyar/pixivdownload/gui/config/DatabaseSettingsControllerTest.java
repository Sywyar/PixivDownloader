package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.*;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DatabaseSettingsControllerTest {
    @Test void secretActionsPreserveKeepAndClearAndChecksDoNotSave() throws Exception {
        var settings = mock(DatabaseSettingsService.class);
        when(settings.check(any())).thenReturn(new CheckResult(Outcome.CONNECTED, "fixture", ""));
        when(settings.editableSettings()).thenReturn(new DatabaseSettingsService.EditableSettings("independent-db", "", "name", true, false));
        var messages = TestI18nBeans.appMessages();
        var mvc = MockMvcBuilders.standaloneSetup(new DatabaseSettingsController(settings,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages)).build();
        mvc.perform(get("/api/settings/database")).andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordStored").value(true));
        mvc.perform(post("/api/settings/database/check").contentType("application/json")
                .content("{\"provider\":\"independent-db\",\"passwordAction\":\"KEEP\"}"))
                .andExpect(status().isOk());
        verify(settings).check(Map.of("database.provider", "independent-db"));
        mvc.perform(post("/api/settings/database/check").contentType("application/json")
                .content("{\"passwordAction\":\"CLEAR\"}"))
                .andExpect(status().isOk());
        verify(settings).check(Map.of("database.password", ""));
        mvc.perform(post("/api/settings/database/check").contentType("application/json")
                .content("{\"passwordAction\":\"SET\",\"password\":\"fixture-secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.password").doesNotExist());
        verify(settings).check(Map.of("database.password", "fixture-secret"));
        verify(settings, never()).prepare(any());
        verify(settings, never()).startMigration(anyString());
    }

    @Test void rejectedDraftAndStalePlanHaveSafeFailures() throws Exception {
        var settings = mock(DatabaseSettingsService.class);
        var messages = TestI18nBeans.appMessages();
        var mvc = MockMvcBuilders.standaloneSetup(new DatabaseSettingsController(settings,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages)).build();
        for (String body : List.of("{\"password\":\"must-not-forward\"}", "{\"passwordAction\":\"SET\"}",
                "{\"passwordAction\":\"CLEAR\",\"password\":\"ambiguous\"}", "{\"download.root-folder\":\"D:/other\"}")) {
            mvc.perform(post("/api/settings/database/check").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(settings);
        doThrow(new java.io.IOException("MIGRATION_PLAN_CHANGED")).when(settings).startMigration("stale");
        mvc.perform(post("/api/settings/database/migrations").contentType("application/json").content("{\"token\":\"stale\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MIGRATION_PLAN_CHANGED"));
        when(settings.editableSettings()).thenThrow(new java.io.IOException("driver output containing fixture-secret"));
        mvc.perform(get("/api/settings/database")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DATABASE_OPERATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-secret"))));
    }

    @Test void confirmationReturnsDurableStateAfterServiceAccepts() throws Exception {
        var settings = mock(DatabaseSettingsService.class);
        when(settings.migrationState()).thenReturn(new MigrationState("PENDING", "", List.of()));
        var messages = TestI18nBeans.appMessages();
        var mvc = MockMvcBuilders.standaloneSetup(new DatabaseSettingsController(settings,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages)).build();
        mvc.perform(post("/api/settings/database/migrations").contentType("application/json").content("{\"token\":\"confirmed\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
        var ordered = inOrder(settings);
        ordered.verify(settings).startMigration("confirmed");
        ordered.verify(settings).migrationState();
    }
}
