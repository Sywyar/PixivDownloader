package top.sywyar.pixivdownload.plugin.recovery;

import top.sywyar.pixivdownload.gui.DesktopUiFailure;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/** 本次进程的结构化诊断；不根据旧日志或任意异常文本猜测插件身份。 */
public record RecoveryGuidance(List<String> adviceKeys, List<String> errors, String desktopSummaryKey) {
    private static final int MAX_ERRORS = 8;
    private static final int MAX_ERROR_LENGTH = 600;

    public static RecoveryGuidance from(
            PluginRecoveryGateSnapshot gate,
            DesktopUiFailure desktopFailure,
            PluginStatusReport plugins,
            List<String> failures
    ) {
        boolean desktopUnavailable = desktopFailure != null;
        var advice = new LinkedHashSet<String>();
        var diagnostics = plugins.diagnostics().stream()
                .filter(plugin -> plugin.requiredByPolicy() || switch (plugin.status()) {
                    case FAILED, CRASHED, INCOMPATIBLE, INCOMPATIBLE_REQUIRED, MISSING_REQUIRED -> true;
                    case DISABLED -> desktopUnavailable;
                    default -> false;
                })
                .sorted(java.util.Comparator.comparing((PluginDiagnostic plugin) ->
                        plugin.status() != PluginStatus.FAILED && plugin.status() != PluginStatus.CRASHED)
                        .thenComparing(plugin -> !plugin.requiredByPolicy()))
                .toList();
        if (!gate.safeToScan()) {
            // 清点被阻断时，缺失和禁用等插件结论不可靠，也不能建议继续安装。
            gate.report().failures().forEach(failure -> advice.add(switch (failure.kind()) {
                case DIRECTORY_IN_USE -> "recovery.advice.directory-in-use";
                case STAGING_ROOT_UNSAFE, INVALID_TRANSACTION_ENTRY, UNSAFE_PATH -> "recovery.advice.unsafe-path";
                case STAGING_ENUMERATION_FAILED -> "recovery.advice.directory-unreadable";
                case MISSING_MANIFEST, INVALID_MANIFEST -> "recovery.advice.manifest";
                case IDENTITY_CONFLICT -> "recovery.advice.identity-conflict";
                case RECOVERY_FAILED -> "recovery.advice.transaction";
            }));
            if (advice.isEmpty()) advice.add("recovery.advice.transaction");
        } else {
            var knownPackages = plugins.diagnostics().stream()
                    .filter(plugin -> plugin.descriptor() != null || plugin.status() != PluginStatus.MISSING_REQUIRED)
                    .map(PluginDiagnostic::id).collect(java.util.stream.Collectors.toSet());
            diagnostics.forEach(plugin -> {
                String key = switch (plugin.status()) {
                    case STARTED -> null;
                    case DISABLED -> plugin.requiredByPolicy() || desktopUnavailable ? "recovery.advice.disabled" : null;
                    case INSTALLED, RESOLVED, LOADED, STOPPED ->
                            plugin.requiredByPolicy() ? "recovery.advice.not-started" : null;
                    case INCOMPATIBLE, INCOMPATIBLE_REQUIRED -> "recovery.advice.incompatible";
                    case MISSING_REQUIRED -> plugin.descriptor() != null ? "recovery.advice.dependency"
                            : knownPackages.contains(plugin.id()) ? null : "recovery.advice.missing";
                    case FAILED -> "recovery.advice.failed";
                    case CRASHED -> "recovery.advice.crashed";
                };
                if (key != null) advice.add(key);
            });
            if (desktopUnavailable) advice.add(desktopFailure.reason().adviceKey());
            if (advice.isEmpty()) advice.add("recovery.advice.unknown");
        }
        Stream<String> transactionErrors = gate.report().failures().stream()
                .map(failure -> failure.kind() + ": " + failure.detail());
        Stream<String> pluginErrors = gate.safeToScan() ? diagnostics.stream()
                .filter(plugin -> plugin.status() != PluginStatus.STARTED)
                .map(plugin -> plugin.id() + " [" + plugin.status() + "] " + String.join("; ", plugin.messages()))
                : Stream.empty();
        Stream<String> desktopErrors = gate.safeToScan() && desktopUnavailable
                ? Stream.of(desktopFailure.detail()) : Stream.empty();
        return new RecoveryGuidance(List.copyOf(advice),
                Stream.concat(transactionErrors, Stream.concat(desktopErrors, Stream.concat(pluginErrors, failures.stream())))
                .filter(value -> value != null && !value.isBlank())
                .map(RecoveryGuidance::summary).distinct().limit(MAX_ERRORS).toList(),
                gate.safeToScan() && desktopUnavailable ? desktopFailure.reason().summaryKey() : null);
    }

    public List<String> localizedErrors(java.util.function.Function<String, String> messages) {
        return Stream.concat(desktopSummaryKey == null ? Stream.empty() : Stream.of(messages.apply(desktopSummaryKey)),
                errors.stream()).map(RecoveryGuidance::summary).distinct().limit(MAX_ERRORS).toList();
    }

    private static String summary(String value) {
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        int end = Math.min(clean.length(), MAX_ERROR_LENGTH);
        if (end < clean.length() && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end) + (end < clean.length() ? "…" : "");
    }
}
