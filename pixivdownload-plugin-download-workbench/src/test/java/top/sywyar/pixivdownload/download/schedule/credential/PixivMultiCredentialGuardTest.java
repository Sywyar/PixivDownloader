package top.sywyar.pixivdownload.download.schedule.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.download.schedule.guard.PixivOveruseExecutionGuard;
import top.sywyar.pixivdownload.download.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialCandidate;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.*;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("计划任务多凭据风险边界")
class PixivMultiCredentialGuardTest {
    private final OveruseWarningService warnings = mock(OveruseWarningService.class);
    private final PixivOveruseExecutionGuard guard = new PixivOveruseExecutionGuard(warnings,
            mock(PixivSchedulePersistenceCodec.class), JsonMapper.builder().build());

    private ScheduledGuardContext context(String source, String backupAccount) {
        var context = mock(ScheduledGuardContext.class);
        var task = mock(ScheduledTaskDefinition.class);
        when(task.sourceType()).thenReturn(source);
        when(context.task()).thenReturn(task);
        when(context.route()).thenReturn(ScheduledNetworkRoute.direct());
        when(context.cancellation()).thenReturn(() -> false);
        when(context.point()).thenReturn(ScheduledGuardPoint.RUN_START);
        when(context.credentialPolicyStateJson()).thenReturn(Optional.empty());
        var credential = mock(ScheduledCredentialHandle.class);
        when(context.credential()).thenReturn(credential);
        when(credential.isPresent()).thenReturn(true);
        when(credential.accountKey()).thenReturn("1");
        when(credential.copyCandidates()).thenAnswer(ignored -> List.of(
                new ScheduledCredentialCandidate("1", "PHPSESSID=1_a".toCharArray()),
                new ScheduledCredentialCandidate(backupAccount, ("PHPSESSID=" + backupAccount + "_b").toCharArray())));
        return context;
    }

    @Test @DisplayName("公开来源可用备用账号，个人来源只能用同账号备用")
    void doesNotChangePersonalSourceAccount() throws Exception {
        when(warnings.checkVerified(eq("PHPSESSID=1_a"), isNull(), anyLong()))
                .thenReturn(OveruseWarningService.Result.cookieDead());
        when(warnings.checkVerified(eq("PHPSESSID=2_b"), isNull(), anyLong()))
                .thenReturn(OveruseWarningService.Result.clean());
        assertThat(guard.evaluate(context("user-new", "2")).decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
        assertThat(guard.evaluate(context("my-bookmarks", "2")).decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL);
        when(warnings.checkVerified(eq("PHPSESSID=1_b"), isNull(), anyLong()))
                .thenReturn(OveruseWarningService.Result.clean());
        assertThat(guard.evaluate(context("my-bookmarks", "1")).decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
    }

    @Test @DisplayName("备用账号风险先停止任务，不误挂起首选账号的其它任务")
    void backupWarningDoesNotSuspendPrimaryAccount() throws Exception {
        when(warnings.checkVerified(eq("PHPSESSID=1_a"), isNull(), anyLong()))
                .thenReturn(OveruseWarningService.Result.clean());
        when(warnings.checkVerified(eq("PHPSESSID=2_b"), isNull(), anyLong()))
                .thenReturn(OveruseWarningService.Result.warned(123L, "warning"));
        assertThat(guard.evaluate(context("user-new", "2")).decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK);
    }

    @Test @DisplayName("全部候选冷却保留凭据并等待，不撤销或匿名继续")
    void cooldownDefersWithoutRevocation() throws Exception {
        when(warnings.checkVerified(anyString(), isNull(), anyLong())).thenThrow(
                new top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException(120_000));
        var decision = guard.evaluate(context("user-new", "2")).decision();
        assertThat(decision.action()).isEqualTo(ScheduledGuardDecision.Action.RETRY_LATER);
        assertThat(decision.retryAfterMillis()).isEqualTo(120_000);
    }
}
