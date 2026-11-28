package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsControllerTest {
    private final SettingsApiService settings = mock(SettingsApiService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc;

    SettingsControllerTest() {
        var messages = TestI18nBeans.appMessages();
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(settings,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages)).build();
    }

    @Test
    @DisplayName("程序批量保存先检查全部目标，不能借 core 权限修改其他插件")
    void rejectsUnselectedOwnerBeforeAnySave() throws Exception {
        var messages = TestI18nBeans.appMessages();
        var controller = new SettingsController(settings, new ToolApiRequests(messages, mock(WebI18nService.class)), messages);
        var request = new MockHttpServletRequest();
        request.setAttribute(ProgramIdentity.REQUEST_ATTRIBUTE, new ProgramIdentity(java.util.UUID.randomUUID(),
                java.util.Set.of("core:settings.write", "allowed:settings.write")));
        request.setContent(("{\"changes\":[{\"owner\":\"allowed\",\"generation\":1,\"key\":\"a\",\"value\":\"yes\"},"
                + "{\"owner\":\"other\",\"generation\":1,\"key\":\"b\",\"value\":\"yes\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(settings.hasSettingsOwner("other")).thenReturn(true);
        assertThatThrownBy(() -> controller.save(request)).isInstanceOfSatisfying(
                top.sywyar.pixivdownload.setup.ProgramAccess.MissingPermission.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.permission()).isEqualTo("other:settings.write"));
        verify(settings).hasSettingsOwner("other");
        verifyNoMoreInteractions(settings);
    }

    @Test
    @DisplayName("预设列表仅返回程序获准读取的目标插件")
    void filtersPresetOwners() throws Exception {
        var messages = TestI18nBeans.appMessages();
        var controller = new SettingsController(settings, new ToolApiRequests(messages, mock(WebI18nService.class)), messages);
        var allowed = new SettingsApiService.Preset("allowed", "allowed", 1, "section", "preset", "example", "label", null, java.util.Map.of());
        var other = new SettingsApiService.Preset("other", "other", 1, "section", "preset", "example", "label", null, java.util.Map.of());
        when(settings.presets(any())).thenAnswer(call -> {
            java.util.function.Predicate<String> permits = call.getArgument(0);
            return List.of(allowed, other).stream().filter(preset -> permits.test(preset.owner())).toList();
        });
        var request = new MockHttpServletRequest();
        request.setAttribute(ProgramIdentity.REQUEST_ATTRIBUTE, new ProgramIdentity(java.util.UUID.randomUUID(),
                java.util.Set.of("core:settings.read", "allowed:settings.read")));
        org.assertj.core.api.Assertions.assertThat(controller.presets(request)).containsExactly(allowed);
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
        when(settings.fields(any())).thenThrow(new IOException("fixture-secret"));
        mvc.perform(get("/api/settings")).andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("fixture-secret"))));
    }
}
