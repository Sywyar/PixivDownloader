package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 宿主交给当前凭证策略规划账号级恢复动作的安全纯值请求。 */
public record ScheduledCredentialAccountActionRequest(
        String accountKey,
        String actionId,
        Map<String, String> parameters,
        long requestedAt,
        List<ScheduledCredentialTaskSnapshot> tasks
) {

    public static final int MAX_ACCOUNT_KEY_BYTES = 1_024;
    public static final int MAX_TASKS = 512;

    public ScheduledCredentialAccountActionRequest {
        accountKey = validateAccountKey(accountKey);
        actionId = validateActionId(actionId);
        parameters = new ScheduledGuardEvidence(parameters).attributes();
        if (requestedAt < 0) {
            throw new IllegalArgumentException("credential account action time must not be negative");
        }
        tasks = validateTasks(tasks, "credential account action");
    }

    static String validateAccountKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("credential account key must not be blank");
        }
        String normalized = value.trim();
        if (normalized.codePoints().anyMatch(Character::isISOControl)
                || ScheduledCredentialText.containsCredentialMaterial(normalized)
                || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_ACCOUNT_KEY_BYTES) {
            throw new IllegalArgumentException("credential account key is invalid or too large");
        }
        return normalized;
    }

    static String validateMachineCode(String value, String label) {
        return ScheduledCredentialTaskSnapshot.validateMachineCode(value, label);
    }

    static List<ScheduledCredentialTaskSnapshot> validateTasks(
            List<ScheduledCredentialTaskSnapshot> values,
            String label) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(label + " tasks must not be empty");
        }
        if (values.size() > MAX_TASKS) {
            throw new IllegalArgumentException(label + " has too many tasks");
        }
        List<ScheduledCredentialTaskSnapshot> copy = List.copyOf(values);
        Set<Long> taskIds = new LinkedHashSet<>();
        for (ScheduledCredentialTaskSnapshot task : copy) {
            if (task == null) {
                throw new IllegalArgumentException(label + " contains a null task");
            }
            if (!taskIds.add(task.taskId())) {
                throw new IllegalArgumentException(label + " contains duplicate task ids");
            }
        }
        return copy;
    }

    private static String validateActionId(String value) {
        return validateMachineCode(value, "credential account action id");
    }
}
