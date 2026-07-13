package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;

import java.nio.charset.StandardCharsets;

/**
 * 凭证绑定探活的一次性安全结果：凭证有效性、首次策略状态，以及绑定成功后宿主应执行的任务级策略决定。
 * 插件只返回纯数据，不直接写宿主任务或凭证存储。
 */
public record ScheduledCredentialBindResult(
        ScheduledCredentialProbeResult probeResult,
        String initialPolicyStateJson,
        ScheduledGuardResult postBindResult
) {

    public static final int MAX_POLICY_STATE_BYTES = 65_536;

    public ScheduledCredentialBindResult {
        if (probeResult == null) {
            throw new IllegalArgumentException("credential bind probe result must not be null");
        }
        initialPolicyStateJson = initialPolicyStateJson == null || initialPolicyStateJson.isBlank()
                ? "{}" : initialPolicyStateJson;
        if (initialPolicyStateJson.indexOf('\0') >= 0
                || initialPolicyStateJson.getBytes(StandardCharsets.UTF_8).length
                > MAX_POLICY_STATE_BYTES) {
            throw new IllegalArgumentException("credential policy state is invalid or too large");
        }
        postBindResult = postBindResult == null
                ? ScheduledGuardResult.decision(ScheduledGuardDecision.proceed())
                : postBindResult;
        ScheduledGuardDecision.Action action = postBindResult.decision().action();
        if (action != ScheduledGuardDecision.Action.CONTINUE
                && action != ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK) {
            throw new IllegalArgumentException("credential bind result has unsupported post-bind action");
        }
        if (probeResult.status() != ScheduledCredentialProbeResult.Status.VALID
                && action != ScheduledGuardDecision.Action.CONTINUE) {
            throw new IllegalArgumentException("invalid credential must not request a post-bind action");
        }
    }

    public static ScheduledCredentialBindResult fromProbe(
            ScheduledCredentialProbeResult probeResult) {
        return new ScheduledCredentialBindResult(probeResult, "{}", null);
    }
}
