package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;

import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PluginMarketController} 单测（MockMvc standalone）：四个市场端点（仓库列表 / catalog / 插件详情 / 安装）+
 * catalog 层失败经 {@code @ExceptionHandler} 映射为稳定 code，以及<b>只接受受控标识、绝不接受任意 URL</b>（安装只按
 * 路径 repositoryId+pluginId+version 解析，请求体里的 url 被忽略）。
 */
@DisplayName("PluginMarketController 插件市场后端 API")
class PluginMarketControllerTest {

    private PluginMarketService marketService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        marketService = mock(PluginMarketService.class);
        AppMessages messages = mock(AppMessages.class);
        AppLocaleResolver localeResolver = mock(AppLocaleResolver.class);
        when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        when(messages.getOrDefault(any(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> "localized:" + inv.getArgument(1));
        PluginInstallResponseMapper mapper = new PluginInstallResponseMapper(messages, localeResolver);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PluginMarketController(marketService, mapper, messages, localeResolver))
                .build();
    }

    @Test
    @DisplayName("GET /api/plugin-market/repositories：主开关状态 + 核心 API 版本 + 默认仓库 + 仓库只读投影")
    void getRepositories() throws Exception {
        when(marketService.repositories()).thenReturn(new PluginMarketRepositoriesView(true, "1.0.0", "official",
                List.of(new PluginMarketRepositoryView("official", "plugin.market.repository.official.name",
                        "https://example.com/manifest.json", true, true, true, false, true,
                        "direct-strict", true, 15000L, 60000L, 1048576L, 104857600L))));

        mockMvc.perform(get("/api/plugin-market/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.coreApiVersion").value("1.0.0"))
                .andExpect(jsonPath("$.defaultRepositoryId").value("official"))
                .andExpect(jsonPath("$.repositories[0].repositoryId").value("official"))
                .andExpect(jsonPath("$.repositories[0].official").value(true))
                .andExpect(jsonPath("$.repositories[0].defaultRepository").value(true))
                .andExpect(jsonPath("$.repositories[0].proxyPolicy").value("direct-strict"))
                .andExpect(jsonPath("$.repositories[0].proxyPolicySupported").value(true));
    }

    @Test
    @DisplayName("GET /api/plugin-market/catalog（缺省 repositoryId → 默认仓库）：200 + 分类计数 + 条目摘要")
    void getCatalogDefault() throws Exception {
        when(marketService.catalog(isNull())).thenReturn(new PluginMarketView("official", true, "1.0.0",
                List.of(new PluginMarketCategoryCount("all", 1)),
                List.of(new PluginMarketEntryView("stats", "stats:nav.label", "stats:plugin.summary", "1.2.3", null,
                        List.of(new PluginMarketPackageView("1.2.3", 4096L, "abcdef", false, "1.0",
                                true, true, List.of(), null, List.of(), null, false))))));

        mockMvc.perform(get("/api/plugin-market/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value("official"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.categories[0].category").value("all"))
                .andExpect(jsonPath("$.entries[0].pluginId").value("stats"))
                .andExpect(jsonPath("$.entries[0].latestVersion").value("1.2.3"))
                .andExpect(jsonPath("$.entries[0].packages[0].compatible").value(true))
                .andExpect(jsonPath("$.entries[0].packages[0].effectiveAfterRestart").value(true));
    }

    @Test
    @DisplayName("GET /api/plugin-market/catalog?repositoryId=x：按受控 repositoryId 解析（仅传 id、绝不传 URL）")
    void getCatalogByRepositoryId() throws Exception {
        when(marketService.catalog("official")).thenReturn(PluginMarketView.disabled());

        mockMvc.perform(get("/api/plugin-market/catalog").param("repositoryId", "official"))
                .andExpect(status().isOk());

        // 仅把 repositoryId 透传给 service；service 内只能解析已配置仓库、绝不接受任意 URL。
        verify(marketService).catalog("official");
    }

    @Test
    @DisplayName("GET /api/plugin-market/catalog 主开关关闭：200 + enabled=false + 空（disabled 视图）")
    void getCatalogDisabled() throws Exception {
        when(marketService.catalog(isNull())).thenReturn(PluginMarketView.disabled());

        mockMvc.perform(get("/api/plugin-market/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    @DisplayName("GET /api/plugin-market/catalog 未知仓库：404 + 稳定 code（UNKNOWN_REPOSITORY）")
    void getCatalogUnknownRepository() throws Exception {
        when(marketService.catalog("ghost")).thenThrow(new PluginCatalogException(
                PluginCatalogErrorCode.UNKNOWN_REPOSITORY, "unknown repository: ghost"));

        mockMvc.perform(get("/api/plugin-market/catalog").param("repositoryId", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_REPOSITORY"))
                .andExpect(jsonPath("$.message").value("localized:plugin.catalog.error.unknown-repository"));
    }

    @Test
    @DisplayName("GET /api/plugin-market/plugins/{repositoryId}/{pluginId}：条目详情 + 版本历史")
    void getPluginDetail() throws Exception {
        when(marketService.pluginDetail("official", "stats")).thenReturn(new PluginMarketEntryView(
                "stats", "stats:nav.label", "stats:plugin.summary", "1.2.3", null,
                List.of(new PluginMarketPackageView("1.2.3", 4096L, "abcdef", false, "1.0",
                        true, true, List.of(), "2026-06-01", List.of("first release"), "stable", false))));

        mockMvc.perform(get("/api/plugin-market/plugins/official/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginId").value("stats"))
                .andExpect(jsonPath("$.packages[0].version").value("1.2.3"))
                .andExpect(jsonPath("$.packages[0].channel").value("stable"))
                .andExpect(jsonPath("$.packages[0].releasedTime").value("2026-06-01"));
    }

    @Test
    @DisplayName("GET /api/plugin-market/plugins/{repositoryId}/{pluginId} 未知插件：404 + 稳定 code（UNKNOWN_PLUGIN）")
    void getPluginDetailUnknown() throws Exception {
        when(marketService.pluginDetail("official", "ghost")).thenThrow(new PluginCatalogException(
                PluginCatalogErrorCode.UNKNOWN_PLUGIN, "ghost", null, "plugin not found"));

        mockMvc.perform(get("/api/plugin-market/plugins/official/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PLUGIN"))
                .andExpect(jsonPath("$.pluginId").value("ghost"));
    }

    @Test
    @DisplayName("POST install 成功：200 + 稳定 outcome（INSTALLED）+ effectiveAfterRestart + 本地化 message")
    void installSuccess() throws Exception {
        when(marketService.install("official", "demo", "1.0.0")).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.INSTALLED, true, true, "demo", "1.0.0", null,
                List.of(), List.of(), List.of("INSTALLED demo 1.0.0")));

        mockMvc.perform(post("/api/plugin-market/official/demo/1.0.0/install"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("INSTALLED"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.effectiveAfterRestart").value(true))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.outcome.installed"));
    }

    @Test
    @DisplayName("POST install 完整性不符：422 + 稳定 outcome（REJECTED_INTEGRITY）（复用安装结果模型）")
    void installIntegrityRejected() throws Exception {
        when(marketService.install("official", "demo", "1.0.0")).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.REJECTED_INTEGRITY, false, false, "demo", "1.0.0", null,
                List.of(), List.of(), List.of("sha-256 mismatch")));

        mockMvc.perform(post("/api/plugin-market/official/demo/1.0.0/install"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED_INTEGRITY"))
                .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    @DisplayName("POST install 未知仓库：404 + 稳定 code（UNKNOWN_REPOSITORY）+ 诊断")
    void installUnknownRepository() throws Exception {
        when(marketService.install("ghost", "demo", "1.0.0")).thenThrow(new PluginCatalogException(
                PluginCatalogErrorCode.UNKNOWN_REPOSITORY, "demo", "1.0.0", "unknown repository: ghost"));

        mockMvc.perform(post("/api/plugin-market/ghost/demo/1.0.0/install"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_REPOSITORY"));
    }

    @Test
    @DisplayName("端点不接受任意 URL：请求体里的 url / packageUrl 被忽略，安装仍只按路径 repositoryId+id+version 解析")
    void ignoresArbitraryUrlInBody() throws Exception {
        when(marketService.install("official", "demo", "1.0.0")).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.INSTALLED, true, true, "demo", "1.0.0", null,
                List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/plugin-market/official/demo/1.0.0/install")
                        .param("url", "http://169.254.169.254/latest/meta-data")
                        .param("packageUrl", "https://evil.example/x.jar")
                        .param("repositoryId", "evil"))
                .andExpect(status().isOk());

        // 仅按路径变量解析；请求体里的 url / packageUrl / repositoryId 完全不参与。
        verify(marketService).install("official", "demo", "1.0.0");
    }
}
