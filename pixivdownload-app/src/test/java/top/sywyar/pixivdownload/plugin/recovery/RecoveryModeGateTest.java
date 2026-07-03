package top.sywyar.pixivdownload.plugin.recovery;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeGate;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

@ExtendWith(MockitoExtension.class)
@DisplayName("恢复模式访问拦截：必选插件未满足时只放行诊断 / 修复入口")
class RecoveryModeGateTest {

    private static final RequiredPlugin DW_REQUIRED = new RequiredPlugin(
            "download-workbench", PluginApiRequirement.of(1, 0), false,
            "plugin.recovery.missing.download-workbench");

    @Mock
    private AppLocaleResolver localeResolver;
    @Mock
    private AppMessages messages;
    @Mock
    private FilterChain chain;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        lenient().when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    /** 恢复模式生效（缺失 download-workbench）的 gate。 */
    private RecoveryModeGate activeGate() {
        RecoveryModeService service = recoveryService(new TestPlugin("core", PluginKind.CORE));
        assertThat(service.isActive()).isTrue();
        return new RecoveryModeGate(service, localeResolver, messages);
    }

    /** 正常运行（download-workbench 在场）的 gate。 */
    private RecoveryModeGate operationalGate() {
        RecoveryModeService service = recoveryService(
                startedDownloadWorkbenchInventory(), new TestPlugin("core", PluginKind.CORE));
        assertThat(service.isActive()).isFalse();
        return new RecoveryModeGate(service, localeResolver, messages);
    }

    private static RecoveryModeService recoveryService(PixivFeaturePlugin... plugins) {
        return recoveryService(PluginInventory.empty(), plugins);
    }

    private static RecoveryModeService recoveryService(PluginInventory inventory, PixivFeaturePlugin... plugins) {
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(DW_REQUIRED));
        PluginRegistry registry = new PluginRegistry(
                List.of(plugins), new PluginToggleProperties(), inventory.toDiscoveryResult());
        return new RecoveryModeService(
                new PluginStatusService(registry, inventory, policy), policy);
    }

    private static PluginInventory startedDownloadWorkbenchInventory() {
        TestPlugin plugin = new TestPlugin("download-workbench");
        PluginDescriptor descriptor = new PluginDescriptor(
                "download-workbench",
                "download-workbench",
                "1.0.0",
                PluginApiRequirement.of(1, 0),
                List.of(),
                "top.sywyar.pixivdownload.download.DownloadWorkbenchPf4jPlugin",
                plugin.displayNamespace(),
                plugin.displayName(),
                plugin.description(),
                plugin.iconKey(),
                plugin.colorToken(),
                plugin.kind());
        return new PluginInventory(List.of(new PluginInstallation(
                descriptor, PluginStatus.STARTED, RecoveryModeGateTest.class.getClassLoader(), plugin)), List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/pixiv-batch.html", "/api/download/pixiv", "/pixiv-gallery.html"})
    @DisplayName("正常运行时本过滤器透明：任意请求都放行")
    void operationalPassesEverything(String path) throws Exception {
        request.setMethod("GET");
        request.setRequestURI(path);

        operationalGate().doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("恢复模式下下载页被拦截：重定向到插件市场、不进入后续过滤链")
    void redirectsDownloadPageToPluginMarket() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/pixiv-batch.html");

        activeGate().doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/plugin-market.html");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("恢复模式下下载 API 被拦截：返回 503 JSON")
    void blocksDownloadApi() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/download/pixiv");

        activeGate().doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).contains("application/json");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("恢复模式下 API 油猴脚本入口被拦截：返回 503 JSON")
    void blocksApiUserscriptEntry() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/scripts/pixiv-helper.user.js");

        activeGate().doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).contains("application/json");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("恢复模式下静态油猴脚本入口被拦截：重定向到插件市场")
    void redirectsStaticUserscriptEntry() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/userscripts/pixiv-helper.user.js");

        activeGate().doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/plugin-market.html");
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health", "/actuator/health/readiness", "/actuator/info",
            "/api/plugins/status", "/api/gui/plugins", "/api/i18n/web",
            "/plugin-market.html", "/plugin-market/plugin-market.css", "/api/plugin-market/repositories",
            "/api/navigation",
            "/js/pixiv-theme.js", "/css/app.css", "/vendor/fonts/fonts.css",
            "/favicon.ico", "/login.html", "/"})
    @DisplayName("恢复模式下诊断 / 修复 / 健康 / 基础静态入口放行")
    void allowsRecoveryEntries(String path) throws Exception {
        request.setMethod("GET");
        request.setRequestURI(path);

        activeGate().doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/auth/login, POST",
            "/api/auth/check, GET",
            "/api/setup/status, GET",
            "/api/setup/init, POST",
            "/login/login.js, GET",
            "/login/login.css, GET",
            "/setup.html, GET",
            "/setup/setup.js, GET",
            "/setup/setup.css, GET"})
    @DisplayName("恢复模式下核心认证 / setup 必需入口放行到后续过滤链（鉴权仍由 AuthFilter / controller 负责）")
    void allowsCoreAuthAndSetupEntries(String path, String method) throws Exception {
        request.setMethod(method);
        request.setRequestURI(path);

        activeGate().doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("恢复模式下 OPTIONS 预检放行")
    void allowsOptionsPreflight() throws Exception {
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/download/pixiv");

        activeGate().doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        private final String id;
        private final PluginKind kind;

        TestPlugin(String id) {
            this(id, PluginKind.FEATURE);
        }

        TestPlugin(String id, PluginKind kind) {
            this.id = id;
            this.kind = kind;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id + ".label";
        }

        @Override
        public String description() {
            return id + ".summary";
        }

        @Override
        public PluginKind kind() {
            return kind;
        }
    }
}
