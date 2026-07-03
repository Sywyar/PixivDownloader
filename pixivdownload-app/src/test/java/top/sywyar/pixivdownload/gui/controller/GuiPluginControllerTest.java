package top.sywyar.pixivdownload.gui.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.I18nBundleResponse;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginApiRequirementView;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementEntry;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementReport;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GuiPluginController} 单测（MockMvc standalone）：{@code GET /api/gui/plugins/status} 把核心
 * {@link PluginManagementService#list()} 投影为 GUI 视图、在服务端解析展示名称（解析不到回退 id）、保持字段稳定，
 * 并对非本机可信请求返回 403。状态语义直接取自被 mock 的服务，本测试不验证状态判定本身（那归 PluginManagementService 自测）。
 */
@DisplayName("GuiPluginController GUI 插件状态只读接口")
class GuiPluginControllerTest {

    private PluginManagementService managementService;
    private WebI18nService webI18nService;
    private MockMvc mockMvc;
    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        managementService = mock(PluginManagementService.class);
        webI18nService = mock(WebI18nService.class);
        AppLocaleResolver localeResolver = mock(AppLocaleResolver.class);
        when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GuiPluginController(managementService, webI18nService, localeResolver))
                .build();
    }

    private static PluginManagementEntry entry(String id, String namespace, String nameKey, String source,
                                               PluginStatus status, PluginRuntimePhase phase, boolean managed,
                                               boolean required, String version) {
        return new PluginManagementEntry(
                id, namespace, nameKey, nameKey, "puzzle", "neutral", version, PluginKind.FEATURE,
                new PluginApiRequirementView(false, true, "(unspecified)"), List.of(),
                source, status, phase, managed, required, !required, List.of(), List.of());
    }

    @Test
    @DisplayName("成功：把状态报告投影为 GUI 视图，服务端解析展示名称，字段稳定（外置 gallery + 外置 stats）")
    void statusProjectsReport() throws Exception {
        when(managementService.list()).thenReturn(new PluginManagementReport(false, List.of(
                entry("gallery", "gallery", "plugin.name", "external",
                        PluginStatus.STARTED, PluginRuntimePhase.STARTED, true, false, "1.0.0"),
                entry("stats", "stats", "plugin.name", "external",
                        PluginStatus.STARTED, PluginRuntimePhase.STARTED, true, false, "1.0.0"))));
        when(webI18nService.loadBundle(eq("gallery"), any()))
                .thenReturn(new I18nBundleResponse("gallery", "en", "zh-CN", Map.of("plugin.name", "Gallery")));
        when(webI18nService.loadBundle(eq("stats"), any()))
                .thenReturn(new I18nBundleResponse("stats", "en", "zh-CN", Map.of("plugin.name", "Statistics")));

        mockMvc.perform(get("/api/gui/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryMode").value(false))
                .andExpect(jsonPath("$.plugins[0].id").value("gallery"))
                .andExpect(jsonPath("$.plugins[0].name").value("Gallery"))
                .andExpect(jsonPath("$.plugins[0].source").value("external"))
                .andExpect(jsonPath("$.plugins[0].status").value("STARTED"))
                .andExpect(jsonPath("$.plugins[0].runtimePhase").value("STARTED"))
                .andExpect(jsonPath("$.plugins[0].managed").value(true))
                .andExpect(jsonPath("$.plugins[0].required").value(false))
                .andExpect(jsonPath("$.plugins[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.plugins[0].verification.status")
                        .value(PluginVerificationProjector.UNVERIFIED_LOCAL))
                .andExpect(jsonPath("$.plugins[1].id").value("stats"))
                .andExpect(jsonPath("$.plugins[1].name").value("Statistics"))
                .andExpect(jsonPath("$.plugins[1].source").value("external"))
                .andExpect(jsonPath("$.plugins[1].status").value("STARTED"))
                .andExpect(jsonPath("$.plugins[1].runtimePhase").value("STARTED"))
                .andExpect(jsonPath("$.plugins[1].managed").value(true))
                .andExpect(jsonPath("$.plugins[1].version").value("1.0.0"))
                .andExpect(jsonPath("$.plugins[1].verification.status")
                        .value(PluginVerificationProjector.UNVERIFIED_LOCAL));
    }

    @Test
    @DisplayName("官方外置插件安装态：mail/ai/tts 使用 plugin.name 解析，GUI 不回退显示 id")
    void officialExternalInstalledNamesResolveFromCanonicalMetadata() throws Exception {
        when(managementService.list()).thenReturn(new PluginManagementReport(false, List.of(
                entry("mail", "mail", "plugin.name", "external",
                        PluginStatus.INSTALLED, PluginRuntimePhase.UNLOADED, true, false, "1.0.0"),
                entry("ai", "ai", "plugin.name", "external",
                        PluginStatus.INSTALLED, PluginRuntimePhase.UNLOADED, true, false, "1.0.0"),
                entry("tts", "tts", "plugin.name", "external",
                        PluginStatus.INSTALLED, PluginRuntimePhase.UNLOADED, true, false, "1.0.0"))));
        when(webI18nService.loadBundle(eq("mail"), any()))
                .thenReturn(new I18nBundleResponse("mail", "en", "zh-CN",
                        Map.of("plugin.name", "Mail Notifications")));
        when(webI18nService.loadBundle(eq("ai"), any()))
                .thenReturn(new I18nBundleResponse("ai", "en", "zh-CN",
                        Map.of("plugin.name", "AI Translation")));
        when(webI18nService.loadBundle(eq("tts"), any()))
                .thenReturn(new I18nBundleResponse("tts", "en", "zh-CN",
                        Map.of("plugin.name", "TTS Narration")));

        mockMvc.perform(get("/api/gui/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[0].id").value("mail"))
                .andExpect(jsonPath("$.plugins[0].name").value("Mail Notifications"))
                .andExpect(jsonPath("$.plugins[1].id").value("ai"))
                .andExpect(jsonPath("$.plugins[1].name").value("AI Translation"))
                .andExpect(jsonPath("$.plugins[2].id").value("tts"))
                .andExpect(jsonPath("$.plugins[2].name").value("TTS Narration"));
    }

    @Test
    @DisplayName("官方外置插件安装态：GUI 经真实 WebI18nService 解析未启动包 bundle，不回退 id")
    void installedOnlyOfficialNameResolvesThroughRealWebI18nService() throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Files.createDirectories(plugins);
        writeInstalledPluginJar(
                plugins.resolve("mail-1.0.0.jar"),
                "mail",
                "邮件通知",
                "Mail Notifications");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        WebI18nService realI18n = new WebI18nService(new WebI18nBundleRegistry(
                new PluginRegistry(List.of()), provider(installer)));
        AppLocaleResolver localeResolver = mock(AppLocaleResolver.class);
        when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new GuiPluginController(managementService, realI18n, localeResolver))
                .build();
        when(managementService.list()).thenReturn(new PluginManagementReport(false, List.of(
                entry("mail", "mail", "plugin.name", "external",
                        PluginStatus.INSTALLED, PluginRuntimePhase.UNLOADED, true, false, "1.0.0"))));

        mvc.perform(get("/api/gui/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[0].id").value("mail"))
                .andExpect(jsonPath("$.plugins[0].name").value("Mail Notifications"));
    }

    @Test
    @DisplayName("展示名称回退：缺 namespace / key 的未安装项不查 i18n、name 回退到插件 id")
    void missingNamespaceFallsBackToId() throws Exception {
        when(managementService.list()).thenReturn(new PluginManagementReport(false, List.of(
                entry("download-workbench", null, null, "not-installed",
                        PluginStatus.MISSING_REQUIRED, null, false, true, null))));

        mockMvc.perform(get("/api/gui/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[0].id").value("download-workbench"))
                .andExpect(jsonPath("$.plugins[0].name").value("download-workbench"))
                .andExpect(jsonPath("$.plugins[0].source").value("not-installed"))
                .andExpect(jsonPath("$.plugins[0].required").value(true));

        verify(webI18nService, never()).loadBundle(any(), any());
    }

    @Test
    @DisplayName("展示名称回退：i18n 解析抛错（不可解析 namespace）时 name 回退到插件 id，不影响整份列表")
    void unresolvableNamespaceFallsBackToId() throws Exception {
        when(managementService.list()).thenReturn(new PluginManagementReport(false, List.of(
                entry("probe", "missing-ns", "nav.label", "external",
                        PluginStatus.STARTED, PluginRuntimePhase.STARTED, true, false, "9.9.9"))));
        when(webI18nService.loadBundle(eq("missing-ns"), any()))
                .thenThrow(new RuntimeException("unsupported namespace"));

        mockMvc.perform(get("/api/gui/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[0].id").value("probe"))
                .andExpect(jsonPath("$.plugins[0].name").value("probe"));
    }

    @Test
    @DisplayName("恢复模式：报告 recoveryMode=true 时响应 recoveryMode 为真")
    void recoveryModeReflected() throws Exception {
        when(managementService.list()).thenReturn(new PluginManagementReport(true, List.of()));

        mockMvc.perform(get("/api/gui/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryMode").value(true))
                .andExpect(jsonPath("$.plugins").isEmpty());
    }

    @Test
    @DisplayName("非本机可信请求 → 403，且不触达状态服务")
    void nonLocalRequestForbidden() throws Exception {
        mockMvc.perform(get("/api/gui/plugins/status")
                        .with(req -> {
                            req.setRemoteAddr("8.8.8.8");
                            return req;
                        }))
                .andExpect(status().isForbidden());

        verify(managementService, never()).list();
    }

    private static ObjectProvider<ExternalPluginInstaller> provider(ExternalPluginInstaller installer) {
        return new ObjectProvider<>() {
            @Override
            public ExternalPluginInstaller getObject(Object... args) {
                return installer;
            }

            @Override
            public ExternalPluginInstaller getIfAvailable() {
                return installer;
            }

            @Override
            public ExternalPluginInstaller getObject() {
                return installer;
            }
        };
    }

    private static void writeInstalledPluginJar(Path path, String pluginId, String zhName, String enName)
            throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            writeEntry(jar, "plugin.properties", """
                    plugin.id=%s
                    plugin.version=1.0.0
                    plugin.class=com.example.%sPlugin
                    plugin.requires=1.0
                    pixiv.display-namespace=%s
                    pixiv.display-name-key=plugin.name
                    pixiv.description-key=plugin.summary
                    pixiv.icon-key=mail
                    pixiv.color-token=green
                    """.formatted(pluginId, pluginId, pluginId));
            writeEntry(jar, "i18n/web/%s.properties".formatted(pluginId),
                    "plugin.name=%s\nplugin.summary=中文简介\n".formatted(zhName));
            writeEntry(jar, "i18n/web/%s_en.properties".formatted(pluginId),
                    "plugin.name=%s\nplugin.summary=English summary\n".formatted(enName));
        }
    }

    private static void writeEntry(JarOutputStream jar, String name, String content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
