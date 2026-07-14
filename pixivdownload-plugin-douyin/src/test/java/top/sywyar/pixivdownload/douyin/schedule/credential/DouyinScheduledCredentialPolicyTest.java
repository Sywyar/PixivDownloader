package top.sywyar.pixivdownload.douyin.schedule.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.model.DouyinAccount;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("抖音计划凭据策略")
class DouyinScheduledCredentialPolicyTest {

    private static final String COOKIE =
            "ttwid=tt-value; passport_csrf_token=csrf-value; sessionid=session-value";

    @Test
    @DisplayName("主动探活沿用同一已解析代理和凭据并清理线程与字符副本")
    void validProbeUsesResolvedRouteAndClearsTemporaryState() throws Exception {
        DouyinClient client = mock(DouyinClient.class);
        ScheduledNetworkRoute route = ScheduledNetworkRoute.proxy("127.0.0.2", 7891, null);
        char[] copiedSecret = COOKIE.toCharArray();
        when(client.resolveAccount(COOKIE)).thenAnswer(invocation -> {
            assertThat(OutboundProxyOverride.isActive()).isTrue();
            assertThat(OutboundProxyOverride.current()).isNotNull();
            assertThat(OutboundProxyOverride.current().getHostName()).isEqualTo("127.0.0.2");
            assertThat(OutboundProxyOverride.current().getPort()).isEqualTo(7891);
            return new DouyinAccount("account-1", "sec-user", "作者", "author");
        });

        ScheduledCredentialProbeResult result = new DouyinScheduledCredentialPolicy(client)
                .probe(context(route, copiedSecret, () -> false));

        assertThat(result.status()).isEqualTo(ScheduledCredentialProbeResult.Status.VALID);
        assertThat(result.accountKey()).isEqualTo("account-1");
        assertThat(copiedSecret).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();
        verify(client).resolveAccount(COOKIE);
    }

    @Test
    @DisplayName("缺字段凭据在本地判无效且不发出账号请求")
    void malformedCredentialIsRejectedLocally() throws Exception {
        DouyinClient client = mock(DouyinClient.class);
        char[] copiedSecret = "ttwid=only-one-field".toCharArray();

        ScheduledCredentialProbeResult result = new DouyinScheduledCredentialPolicy(client)
                .probe(context(ScheduledNetworkRoute.direct(), copiedSecret, () -> false));

        assertThat(result.status()).isEqualTo(ScheduledCredentialProbeResult.Status.INVALID);
        assertThat(result.code()).isEqualTo("douyin.credential.fields-missing");
        assertThat(copiedSecret).containsOnly('\0');
        verify(client, never()).resolveAccount(anyString());
    }

    @Test
    @DisplayName("过期凭据与限流分别归一为无效和延后重试")
    void credentialAndRateFailuresHaveStableResults() throws Exception {
        DouyinClient expiredClient = failingClient(DouyinClientErrorCode.COOKIE_EXPIRED);
        ScheduledCredentialProbeResult expired = new DouyinScheduledCredentialPolicy(expiredClient)
                .probe(context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> false));
        assertThat(expired.status()).isEqualTo(ScheduledCredentialProbeResult.Status.INVALID);
        assertThat(expired.code()).isEqualTo("douyin.credential.invalid");

        DouyinClient limitedClient = failingClient(DouyinClientErrorCode.RATE_LIMITED);
        ScheduledCredentialProbeResult limited = new DouyinScheduledCredentialPolicy(limitedClient)
                .probe(context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> false));
        assertThat(limited.status()).isEqualTo(ScheduledCredentialProbeResult.Status.RETRY_LATER);
        assertThat(limited.retryAfterMillis()).isEqualTo(15L * 60L * 1_000L);
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("挑战响应只暴露安全失败分类而不泄漏上游文本")
    void challengeFailureIsSafe() throws Exception {
        DouyinClient client = mock(DouyinClient.class);
        when(client.resolveAccount(COOKIE)).thenThrow(new DouyinClientException(
                DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                "upstream body contains " + COOKIE));

        assertThatThrownBy(() -> new DouyinScheduledCredentialPolicy(client)
                .probe(context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> false)))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.CHALLENGE);
                    assertThat(failure.code()).isEqualTo("douyin.schedule.challenge");
                    assertThat(failure.getMessage()).doesNotContain(COOKIE);
                });
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("远端账号键若疑似包含凭据材料则不得进入宿主状态")
    void unsafeAccountKeyIsRejected() throws Exception {
        DouyinClient client = mock(DouyinClient.class);
        when(client.resolveAccount(COOKIE)).thenReturn(
                new DouyinAccount("sessionid=leaked-value", "sec-user", "作者", "author"));

        ScheduledCredentialProbeResult result = new DouyinScheduledCredentialPolicy(client)
                .probe(context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> false));

        assertThat(result.status()).isEqualTo(ScheduledCredentialProbeResult.Status.INVALID);
        assertThat(result.accountKey()).isNull();
    }

    @Test
    @DisplayName("取消信号在联网前终止探活并保持路由清洁")
    void cancellationStopsProbeBeforeNetwork() throws Exception {
        DouyinClient client = mock(DouyinClient.class);

        assertThatThrownBy(() -> new DouyinScheduledCredentialPolicy(client)
                .probe(context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> true)))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.category())
                                .isEqualTo(ScheduledFailure.Category.CANCELLED));
        verify(client, never()).resolveAccount(anyString());
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    private static DouyinClient failingClient(DouyinClientErrorCode code)
            throws DouyinClientException {
        DouyinClient client = mock(DouyinClient.class);
        when(client.resolveAccount(COOKIE)).thenThrow(new DouyinClientException(code, "upstream"));
        return client;
    }

    private static ScheduledCredentialContext context(
            ScheduledNetworkRoute route,
            char[] copiedSecret,
            ScheduledCancellation cancellation) {
        ScheduledCredentialHandle handle = mock(ScheduledCredentialHandle.class);
        when(handle.isPresent()).thenReturn(copiedSecret.length > 0);
        when(handle.copySecret()).thenReturn(copiedSecret);
        ScheduledCredentialContext context = mock(ScheduledCredentialContext.class);
        when(context.purpose()).thenReturn(ScheduledCredentialContext.Purpose.RUN_START);
        when(context.route()).thenReturn(route);
        when(context.credential()).thenReturn(handle);
        when(context.cancellation()).thenReturn(cancellation);
        return context;
    }
}
