package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号策略事件的安全通知投影。普通值与 epoch 毫秒时间值分离，宿主只按 key 执行通用 i18n 格式化，
 * 不解释来源插件的 evidence 或策略状态。
 */
public record ScheduledCredentialIncidentPresentation(
        String scenarioId,
        Map<String, String> scalarAttributes,
        Map<String, Long> timeAttributes
) {

    public static final int MAX_ATTRIBUTES = ScheduledGuardEvidence.MAX_ATTRIBUTES;

    public ScheduledCredentialIncidentPresentation {
        if (scenarioId == null || scenarioId.isBlank()) {
            scenarioId = null;
        } else {
            scenarioId = ScheduledCredentialAccountActionRequest.validateMachineCode(
                    scenarioId, "credential incident scenario id");
        }
        scalarAttributes = new ScheduledGuardEvidence(scalarAttributes).attributes();
        timeAttributes = validateTimeAttributes(timeAttributes);
        if (scalarAttributes.size() + timeAttributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException(
                    "credential incident presentation has too many attributes");
        }
        for (String key : timeAttributes.keySet()) {
            if (scalarAttributes.containsKey(key)) {
                throw new IllegalArgumentException(
                        "credential incident presentation has duplicate attribute keys");
            }
        }
        if (scenarioId == null
                && (!scalarAttributes.isEmpty() || !timeAttributes.isEmpty())) {
            throw new IllegalArgumentException(
                    "empty credential incident presentation must not carry attributes");
        }
    }

    public static ScheduledCredentialIncidentPresentation empty() {
        return new ScheduledCredentialIncidentPresentation(null, Map.of(), Map.of());
    }

    private static Map<String, Long> validateTimeAttributes(Map<String, Long> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> encoded = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            Long value = entry.getValue();
            if (value == null || value < 0) {
                throw new IllegalArgumentException(
                        "credential incident time attribute must not be negative");
            }
            encoded.put(entry.getKey(), Long.toString(value));
        }
        Map<String, String> validated = new ScheduledGuardEvidence(encoded).attributes();
        Map<String, Long> copy = new LinkedHashMap<>();
        validated.forEach((key, value) -> copy.put(key, Long.parseLong(value)));
        return Map.copyOf(copy);
    }
}
