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
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
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

/**
 * 以「核心策略把 {@code recovery-sentinel} 声明为必选、而该插件缺失」驱动恢复模式，验证恢复模式访问拦截
 * {@link RecoveryModeGate} 的放行 / 拦截清单。这条路径走<b>真实</b> {@link RecoveryModeService} +
 * {@link PluginStatusService} + 恢复模式评估器：策略只含 recovery-sentinel，注册中心不含它（模拟未安装）、清点为空，
 * 故评估为 {@link PluginStatus#MISSING_REQUIRED}、进入恢复模式。
 *
 * <p>下载工作台等内置必选插件不在本测试策略内，故 firstReason 必为 recovery-sentinel——验证「该外置插件缺失」
 * 这一具体原因能独立触发恢复模式与请求拦截。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("recovery-sentinel 缺失驱动恢复模式：业务 / 下载 / 油猴脚本入口 503，认证 / setup 入口放行")
class RecoverySentinelRecoveryGateTest {

    private static final RequiredPlugin SENTINEL_REQUIRED = new RequiredPlugin(
            "recovery-sentinel", PluginApiRequirement.unspecified(), false, "plugin.recovery.blocked");

    @Mock
    private AppLocaleResolver localeResolver;
    @Mock
    private AppMessages messages;
    @Mock
    private FilterChain chain;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private RecoveryModeService recoveryModeService;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        lenient().when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));

        // 策略只含 recovery-sentinel，注册中心仅含一个核心插件（无 recovery-sentinel）、清点为空 → 缺失 → 恢复模式。
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(SENTINEL_REQUIRED));
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), new PluginToggleProperties());
        recoveryModeService = new RecoveryModeService(
                new PluginStatusService(registry, PluginInventory.empty(), policy), policy);
    }

    private RecoveryModeGate gate() {
        return new RecoveryModeGate(recoveryModeService, localeResolver, messages);
    }

    @Test
    @DisplayName("recovery-sentinel 必选但缺失：判定进入恢复模式，首要原因为 recovery-sentinel（MISSING_REQUIRED）")
    void missingRequiredSentinelActivatesRecovery() {
        assertThat(recoveryModeService.isActive()).isTrue();
        assertThat(recoveryModeService.decision().firstReason().orElseThrow().pluginId())
                .isEqualTo("recovery-sentinel");
        assertThat(recoveryModeService.decision().firstReason().orElseThrow().status())
                .isEqualTo(PluginStatus.MISSING_REQUIRED);
    }

    @Test
    @DisplayName("下载页 /pixiv-batch.html 被拦截：503 提示页，不进入后续链路")
    void blocksDownloadPage() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/pixiv-batch.html");

        gate().doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).contains("text/html");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("下载 API /api/download/pixiv 被拦截：503 JSON，不进入后续链路")
    void blocksDownloadApi() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/download/pixiv");

        gate().doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).contains("application/json");
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/scripts/pixiv-helper.user.js", "/userscripts/pixiv-helper.user.js"})
    @DisplayName("油猴脚本入口 /api/scripts/** 与 /userscripts/** 被拦截：503")
    void blocksUserscriptEntries(String path) throws Exception {
        request.setMethod("GET");
        request.setRequestURI(path);

        gate().doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
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
            "/setup/setup.js, GET"})
    @DisplayName("核心认证 / setup 入口放行到后续链路（鉴权仍由 AuthFilter / controller 负责）")
    void allowsCoreAuthAndSetupEntries(String path, String method) throws Exception {
        request.setMethod(method);
        request.setRequestURI(path);

        gate().doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        private final String id;
        private final PluginKind kind;

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
