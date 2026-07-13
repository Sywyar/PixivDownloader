package top.sywyar.pixivdownload.download.schedule.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Pixiv 计划凭证绑定策略")
class PixivScheduledCredentialPolicyTest {

    private static final String COOKIE = "PHPSESSID=12345_session; other=value";

    @Test
    @DisplayName("绑定探活 clean 时经 resolved route 返回有效账号且只联网一次")
    void cleanBindProbeUsesResolvedRouteOnce() throws Exception {
        OveruseWarningService warningService = mock(OveruseWarningService.class);
        when(warningService.probe(eq(COOKIE), anyLong())).thenAnswer(invocation -> {
            assertThat(OutboundProxyOverride.current()).isNotNull();
            assertThat(OutboundProxyOverride.current().getHostName()).isEqualTo("127.0.0.2");
            assertThat(OutboundProxyOverride.current().getPort()).isEqualTo(7891);
            return OveruseWarningService.Result.clean();
        });
        PixivScheduledCredentialPolicy policy = policy(warningService);

        var result = policy.probeForBinding(context(
                ScheduledCredentialContext.Purpose.BIND, COOKIE));

        assertThat(result.probeResult().status())
                .isEqualTo(ScheduledCredentialProbeResult.Status.VALID);
        assertThat(result.probeResult().accountKey()).isEqualTo("12345");
        assertThat(result.postBindResult().decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
        assertThat(result.initialPolicyStateJson()).contains("credential-policy-state");
        verify(warningService).probe(eq(COOKIE), anyLong());
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("绑定探活 dead 时返回无效凭证且不携带挂起决定")
    void deadBindProbeIsInvalid() throws Exception {
        OveruseWarningService warningService = mock(OveruseWarningService.class);
        when(warningService.probe(eq(COOKIE), anyLong()))
                .thenReturn(OveruseWarningService.Result.cookieDead());

        var result = policy(warningService).probeForBinding(context(
                ScheduledCredentialContext.Purpose.BIND, COOKIE));

        assertThat(result.probeResult().status())
                .isEqualTo(ScheduledCredentialProbeResult.Status.INVALID);
        assertThat(result.postBindResult().decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
        verify(warningService).probe(eq(COOKIE), anyLong());
    }

    @Test
    @DisplayName("绑定探活 warned 时一次响应同时保留任务挂起决定和安全 evidence")
    void warnedBindProbeKeepsEvidence() throws Exception {
        OveruseWarningService warningService = mock(OveruseWarningService.class);
        when(warningService.probe(eq(COOKIE), anyLong()))
                .thenReturn(OveruseWarningService.Result.warned(999_000L, "safe excerpt"));

        var result = policy(warningService).probeForBinding(context(
                ScheduledCredentialContext.Purpose.BIND, COOKIE));

        assertThat(result.probeResult().status())
                .isEqualTo(ScheduledCredentialProbeResult.Status.VALID);
        assertThat(result.postBindResult().decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK);
        assertThat(result.postBindResult().decision().reasonCode()).isEqualTo("PIXIV_OVERUSE");
        assertThat(result.postBindResult().evidence().attributes())
                .containsEntry("modifiedAt", "999000")
                .containsEntry("excerpt", "safe excerpt");
        verify(warningService).probe(eq(COOKIE), anyLong());
    }

    @Test
    @DisplayName("绑定网络失败转安全可重试异常且运行前格式检查不联网")
    void networkFailureIsSafeAndRunStartIsLocal() throws Exception {
        OveruseWarningService warningService = mock(OveruseWarningService.class);
        when(warningService.probe(eq(COOKIE), anyLong()))
                .thenThrow(new IllegalStateException("network unavailable"));
        PixivScheduledCredentialPolicy policy = policy(warningService);

        assertThatThrownBy(() -> policy.probeForBinding(context(
                ScheduledCredentialContext.Purpose.BIND, COOKIE)))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("pixiv.credential.probe-failed");
                    assertThat(failure.getMessage()).doesNotContain(COOKIE);
                });

        OveruseWarningService localOnly = mock(OveruseWarningService.class);
        ScheduledCredentialProbeResult runStart = policy(localOnly).probe(context(
                ScheduledCredentialContext.Purpose.RUN_START, COOKIE));
        assertThat(runStart.status()).isEqualTo(ScheduledCredentialProbeResult.Status.VALID);
        verify(localOnly, never()).probe(eq(COOKIE), anyLong());
    }

    private static PixivScheduledCredentialPolicy policy(OveruseWarningService warningService) {
        return new PixivScheduledCredentialPolicy(
                warningService, new PixivSchedulePersistenceCodec(new ObjectMapper()));
    }

    private static ScheduledCredentialContext context(
            ScheduledCredentialContext.Purpose purpose,
            String secret) {
        ScheduledTaskDefinition task = new ScheduledTaskDefinition(
                42L, "user-new", "fixture.definition", 1, "{}",
                ScheduledTaskPresentation.empty());
        return new ScheduledCredentialContext() {
            @Override
            public Purpose purpose() {
                return purpose;
            }

            @Override
            public ScheduledTaskDefinition task() {
                return task;
            }

            @Override
            public ScheduledNetworkRoute route() {
                return ScheduledNetworkRoute.proxy("127.0.0.2", 7891, null);
            }

            @Override
            public ScheduledCredentialHandle credential() {
                return new ScheduledCredentialHandle() {
                    private char[] value = secret.toCharArray();

                    @Override
                    public boolean isPresent() {
                        return value.length > 0;
                    }

                    @Override
                    public String reference() {
                        return "fixture-reference";
                    }

                    @Override
                    public String accountKey() {
                        return null;
                    }

                    @Override
                    public char[] copySecret() {
                        return Arrays.copyOf(value, value.length);
                    }

                    @Override
                    public void close() {
                        Arrays.fill(value, '\0');
                        value = new char[0];
                    }
                };
            }

            @Override
            public ScheduledCancellation cancellation() {
                return () -> false;
            }
        };
    }
}
