package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

class SettingsControllerTest {
    private final SettingsApiService settings = mock(SettingsApiService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc;

    SettingsControllerTest() {
        var messages = TestI18nBeans.appMessages();
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(settings,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages)).build();
    }

    @Test void strictInputRejectsUnknownFieldsDuplicatesAndOversizedBodiesBeforeSaving() throws Exception {
        for (String body : List.of("null", "{\"changes\":[],\"arbitrary\":true}",
                "{\"changes\":[],\"changes\":[]}", "{\"changes\":[]} {}",
                "{\"changes\":[{\"generation\":\"1\"}]}")) {
            mvc.perform(post("/api/settings").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/settings").contentType("application/json").content(" ".repeat(256 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("HTTP_BODY_LIMIT"));
        verifyNoInteractions(settings);
    }

    @Test void validationAndSavingAreDistinctAndErrorsRemainStructuredWithoutSecrets() throws Exception {
        String body = "{\"changes\":[{\"generation\":0,\"key\":\"server.port\",\"value\":\"9090\"}]}";
        var changes = List.of(new SettingsApiService.Change(null, null, 0, "server.port", null, "9090"));
        mvc.perform(post("/api/settings/validate").contentType("application/json").content(body))
                .andExpect(status().isOk());
        verify(settings).save(changes, true);
        verify(settings, never()).save(any(), eq(false));
        doThrow(new SettingsApiService.InvalidField("INVALID_SETTINGS_VALUE", null, "server.port"))
                .when(settings).save(changes, false);
        mvc.perform(post("/api/settings").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.key").value("server.port"))
                .andExpect(jsonPath("$.code").value("INVALID_SETTINGS_VALUE"));
        doThrow(new IOException("DATABASE_MIGRATION_REQUIRED")).when(settings).save(changes, false);
        mvc.perform(post("/api/settings").contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATABASE_MIGRATION_REQUIRED"));
        when(settings.fields()).thenThrow(new IOException("fixture-secret"));
        mvc.perform(get("/api/settings")).andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("fixture-secret"))));
    }
}
