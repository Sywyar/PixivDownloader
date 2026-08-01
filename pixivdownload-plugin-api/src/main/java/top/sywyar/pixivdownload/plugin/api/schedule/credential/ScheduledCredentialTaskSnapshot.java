package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 凭证策略纯函数回调使用的最小任务快照。快照只携带宿主盖章的并发身份、机器态和策略自有安全状态，
 * 不包含凭证 secret、宿主持久化类型或插件实现对象。
 */
public record ScheduledCredentialTaskSnapshot(
        long taskId,
        long stateVersion,
        boolean credentialSuspended,
        boolean policySuspended,
        boolean busy,
        String suspendCode,
        String suspendDetailJson,
        String policyStateJson
) {

    public static final int MAX_CODE_BYTES = 128;
    public static final int MAX_DETAIL_BYTES = 16_384;
    public static final int MAX_POLICY_STATE_BYTES = 65_536;

    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,127}");

    public ScheduledCredentialTaskSnapshot {
        if (taskId <= 0) {
            throw new IllegalArgumentException("credential task id must be positive");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("credential task state version must not be negative");
        }
        if (credentialSuspended && policySuspended) {
            throw new IllegalArgumentException(
                    "credential task cannot have two suspension classifications");
        }
        suspendCode = suspendCode == null || suspendCode.isBlank()
                ? null
                : validateMachineCode(suspendCode, "credential task suspend code");
        suspendDetailJson = validateSafeText(
                suspendDetailJson, "credential task suspend detail", MAX_DETAIL_BYTES, false);
        policyStateJson = validateSafeText(
                policyStateJson, "credential task policy state", MAX_POLICY_STATE_BYTES, false);
    }

    static String validateSafeText(
            String value,
            String label,
            int maxBytes,
            boolean trim) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = trim ? value.trim() : value;
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must not contain NUL");
        }
        if (ScheduledCredentialText.containsCredentialMaterial(normalized)) {
            throw new IllegalArgumentException(label + " must not contain credential material");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds size limit");
        }
        return normalized;
    }

    static String validateMachineCode(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = value.trim();
        if (!MACHINE_CODE.matcher(normalized).matches()
                || ScheduledSensitiveFieldNames.isSensitiveFieldName(normalized)
                || ScheduledCredentialText.containsCredentialMaterial(normalized)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }
}
