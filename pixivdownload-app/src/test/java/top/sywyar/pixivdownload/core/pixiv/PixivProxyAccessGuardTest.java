package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivProxyAccessGuard 请求无关策略")
class PixivProxyAccessGuardTest {

    @Mock
    private SetupService setupService;

    @Mock
    private UserQuotaService userQuotaService;

    private MultiModeConfig multiModeConfig;
    private PixivProxyAccessPolicy policy;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.US);
        multiModeConfig = new MultiModeConfig();
        multiModeConfig.setLimitPage(4);
        multiModeConfig.getQuota().setMaxProxyRequests(12);
        multiModeConfig.getQuota().setResetPeriodHours(6);
        policy = new PixivProxyAccessGuard(
                setupService, userQuotaService, multiModeConfig, TestI18nBeans.appMessages());
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("solo 模式不要求 owner 且不消耗配额")
    void shouldAllowSoloWithoutQuotaReservation() {
        when(setupService.getMode()).thenReturn("solo");

        PixivProxyAccessDecision decision = policy.evaluate(null, false);

        assertThat(decision.outcome()).isEqualTo(PixivProxyAccessOutcome.ALLOWED);
        verify(userQuotaService, never()).checkAndReserveProxy(org.mockito.ArgumentMatchers.anyString());
        assertThat(policy.resolveSearchFillLimitPage(false)).isZero();
    }

    @Test
    @DisplayName("multi 管理员不要求 owner、不消耗配额且不限制补页")
    void shouldAllowAuthenticatedAdminWithoutQuotaReservation() {
        when(setupService.getMode()).thenReturn("multi");

        PixivProxyAccessDecision decision = policy.evaluate(null, true);

        assertThat(decision.outcome()).isEqualTo(PixivProxyAccessOutcome.ALLOWED);
        verify(userQuotaService, never()).checkAndReserveProxy(org.mockito.ArgumentMatchers.anyString());
        assertThat(policy.resolveSearchFillLimitPage(true)).isZero();
    }

    @Test
    @DisplayName("multi 游客缺少现有 owner UUID 时拒绝且不生成身份")
    void shouldRequireExistingOwnerForVisitor() {
        when(setupService.getMode()).thenReturn("multi");

        PixivProxyAccessDecision decision = policy.evaluate(null, false);

        assertThat(decision.outcome()).isEqualTo(PixivProxyAccessOutcome.OWNER_REQUIRED);
        assertThat(decision.errorMessage()).isEqualTo("missing user UUID");
        verify(userQuotaService, never()).checkAndReserveProxy(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("multi 游客已有 owner 且配额允许时放行并恰好预留一次")
    void shouldAllowExistingOwnerAfterSingleQuotaReservation() {
        when(setupService.getMode()).thenReturn("multi");
        when(userQuotaService.checkAndReserveProxy("owner-1")).thenReturn(true);

        PixivProxyAccessDecision decision = policy.evaluate("owner-1", false);

        assertThat(decision.outcome()).isEqualTo(PixivProxyAccessOutcome.ALLOWED);
        verify(userQuotaService).checkAndReserveProxy("owner-1");
    }

    @Test
    @DisplayName("multi 游客配额耗尽时返回稳定限流详情")
    void shouldReturnRateLimitDetailsWhenQuotaIsExhausted() {
        when(setupService.getMode()).thenReturn("multi");
        when(userQuotaService.checkAndReserveProxy("owner-1")).thenReturn(false);

        PixivProxyAccessDecision decision = policy.evaluate("owner-1", false);

        assertThat(decision.outcome()).isEqualTo(PixivProxyAccessOutcome.RATE_LIMITED);
        assertThat(decision.errorMessage()).contains("max 12 times per 6 hours");
        assertThat(decision.maxRequests()).isEqualTo(12);
        assertThat(decision.windowHours()).isEqualTo(6);
        assertThat(policy.resolveSearchFillLimitPage(false)).isEqualTo(4);
        verify(userQuotaService).checkAndReserveProxy("owner-1");
    }
}
