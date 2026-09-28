package top.sywyar.pixivdownload.plugin.catalog.operation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PluginAcquisitionControllerTest {
    @Test
    @DisplayName("准备与执行显式分开，核心查询保留原身份和错误并按请求语言展示")
    void routesPreserveOperationAndLocalizeRetainedFailure() throws Exception {
        var operations = mock(PluginAcquisitionOperations.class);
        var messages = mock(AppMessages.class);
        var locales = mock(AppLocaleResolver.class);
        when(locales.resolveLocale(any())).thenAnswer(call -> "ja".equals(
                ((jakarta.servlet.http.HttpServletRequest) call.getArgument(0)).getHeader("Accept-Language"))
                ? Locale.JAPANESE : Locale.ENGLISH);
        when(messages.getOrDefault(any(Locale.class), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0) + ":" + call.getArgument(1));
        var mvc = MockMvcBuilders.standaloneSetup(new PluginAcquisitionController(operations,
                new PluginInstallResponseMapper(messages, locales), messages, locales)).build();
        var snapshot = new PluginAcquisitionOperations.Snapshot("operation", "official", "sample", "1.0.0", "sample",
                ExternalPluginOperation.FAILED, "original-transaction", Instant.EPOCH, Instant.EPOCH, true, true, null,
                new PluginAcquisitionOperations.Failure(PluginCatalogErrorCode.DOWNLOAD_FAILED, "sample", "1.0.0", List.of()));
        when(operations.prepare("official", "sample", "1.0.0", "fingerprint", null)).thenReturn(snapshot);
        when(operations.execute("operation")).thenReturn(snapshot);
        when(operations.get("operation")).thenReturn(snapshot);
        when(operations.list()).thenReturn(List.of(snapshot));
        mvc.perform(post("/api/plugin-market/operations").contentType("application/json").content("""
                {"repositoryId":"official","pluginId":"sample","version":"1.0.0","fingerprint":"fingerprint"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("operation"));
        verify(operations, never()).execute(anyString());
        mvc.perform(post("/api/plugin-market/operations/operation/execute"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transactionId").value("original-transaction"));
        mvc.perform(get("/api/plugins/acquisitions/operation").header("Accept-Language", "ja"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failure.code").value("DOWNLOAD_FAILED"))
                .andExpect(jsonPath("$.failure.error").value("ja:plugin.catalog.error.download-failed"));
        mvc.perform(get("/api/plugins/acquisitions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("operation"));
        when(operations.get("expired")).thenThrow(new PluginCatalogException(
                PluginCatalogErrorCode.OPERATION_NOT_FOUND, "expired"));
        mvc.perform(get("/api/plugins/acquisitions/expired"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
        verify(operations, times(1)).execute(anyString());
    }
}
