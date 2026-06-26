package top.sywyar.pixivdownload.plugin.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;

import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PluginCatalogController} 单测（MockMvc standalone）：GET 目录摘要（未启用 / 启用）、POST 按 id+version 安装
 * （成功复用 {@code PluginInstallResponse}、catalog 失败经 {@code @ExceptionHandler} 映射为稳定 code），以及
 * <b>端点不接受任意 URL</b>（请求体里的 url 参数被忽略，安装仍只按路径 id+version 解析）。
 */
@DisplayName("PluginCatalogController 受信目录后端 API")
class PluginCatalogControllerTest {

    private PluginCatalogAcquisitionService acquisitionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        acquisitionService = mock(PluginCatalogAcquisitionService.class);
        AppMessages messages = mock(AppMessages.class);
        AppLocaleResolver localeResolver = mock(AppLocaleResolver.class);
        when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        when(messages.getOrDefault(any(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> "localized:" + inv.getArgument(1));
        PluginInstallResponseMapper mapper = new PluginInstallResponseMapper(messages, localeResolver);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PluginCatalogController(acquisitionService, mapper, messages, localeResolver))
                .build();
    }

    @Test
    @DisplayName("GET /api/plugins/catalog 未启用：200 + enabled=false + 空 entries")
    void getCatalogDisabled() throws Exception {
        when(acquisitionService.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/plugins/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    @DisplayName("GET /api/plugins/catalog 启用：200 + enabled=true + 条目摘要（含兼容标记、不含市场字段）")
    void getCatalogEnabled() throws Exception {
        when(acquisitionService.isEnabled()).thenReturn(true);
        when(acquisitionService.loadManifest()).thenReturn(new PluginCatalogManifest("1", List.of(
                new PluginCatalogEntry("stats", "stats:nav.label", "stats:plugin.summary", null, List.of(
                        new PluginCatalogPackage("1.2.3", "https://example.com/stats-1.2.3.jar",
                                4096L, "abcdef", null, "1.0", List.of(), null, List.of()))))));

        mockMvc.perform(get("/api/plugins/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.entries[0].pluginId").value("stats"))
                .andExpect(jsonPath("$.entries[0].displayNameKey").value("stats:nav.label"))
                .andExpect(jsonPath("$.entries[0].packages[0].version").value("1.2.3"))
                .andExpect(jsonPath("$.entries[0].packages[0].expectedSizeBytes").value(4096))
                .andExpect(jsonPath("$.entries[0].packages[0].compatible").value(true))
                .andExpect(jsonPath("$.entries[0].packages[0].effectiveAfterRestart").value(true))
                .andExpect(jsonPath("$.entries[0].packages[0].signaturePresent").value(false));
    }

    @Test
    @DisplayName("POST install 成功：200 + 稳定 outcome（INSTALLED）+ effectiveAfterRestart + 本地化 message")
    void installSuccess() throws Exception {
        when(acquisitionService.install("demo", "1.0.0")).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.INSTALLED, true, true, "demo", "1.0.0", null,
                List.of(), List.of(), List.of("INSTALLED demo 1.0.0")));

        mockMvc.perform(post("/api/plugins/catalog/demo/1.0.0/install"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("INSTALLED"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.effectiveAfterRestart").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.outcome.installed"));
    }

    @Test
    @DisplayName("POST install 完整性不符：200 路由命中、422 + 稳定 outcome（REJECTED_INTEGRITY）（复用安装结果模型）")
    void installIntegrityRejected() throws Exception {
        when(acquisitionService.install("demo", "1.0.0")).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.REJECTED_INTEGRITY, false, false, "demo", "1.0.0", null,
                List.of(), List.of(), List.of("sha-256 mismatch")));

        mockMvc.perform(post("/api/plugins/catalog/demo/1.0.0/install"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED_INTEGRITY"))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.outcome.rejected-integrity"));
    }

    @Test
    @DisplayName("POST install 未知插件：404 + 稳定 code（UNKNOWN_PLUGIN）+ 本地化 message + 诊断 id/version")
    void installUnknownPlugin() throws Exception {
        when(acquisitionService.install("ghost", "1.0.0")).thenThrow(new PluginCatalogException(
                PluginCatalogErrorCode.UNKNOWN_PLUGIN, "ghost", "1.0.0", "plugin not found"));

        mockMvc.perform(post("/api/plugins/catalog/ghost/1.0.0/install"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PLUGIN"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.pluginId").value("ghost"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.message").value("localized:plugin.catalog.error.unknown-plugin"));
    }

    @Test
    @DisplayName("POST install 不安全地址：422 + 稳定 code（BLOCKED_ADDRESS）")
    void installBlockedAddress() throws Exception {
        when(acquisitionService.install("demo", "1.0.0")).thenThrow(new PluginCatalogException(
                PluginCatalogErrorCode.BLOCKED_ADDRESS, "demo", "1.0.0", "blocked"));

        mockMvc.perform(post("/api/plugins/catalog/demo/1.0.0/install"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BLOCKED_ADDRESS"))
                .andExpect(jsonPath("$.message").value("localized:plugin.catalog.error.blocked-address"));
    }

    @Test
    @DisplayName("端点不接受任意 URL：请求体里的 url 参数被忽略，安装仍只按路径 id+version 解析")
    void ignoresArbitraryUrlInBody() throws Exception {
        when(acquisitionService.install("demo", "1.0.0")).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.INSTALLED, true, true, "demo", "1.0.0", null,
                List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/plugins/catalog/demo/1.0.0/install")
                        .param("url", "http://169.254.169.254/latest/meta-data")
                        .param("packageUrl", "https://evil.example/x.jar"))
                .andExpect(status().isOk());

        // 仅按路径 id+version 解析；请求体里的 url / packageUrl 完全不参与。
        verify(acquisitionService).install("demo", "1.0.0");
    }
}
