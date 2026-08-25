package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialIncidentPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 计划插件回调的异常归一、fatal 延迟传播与凭证回显过滤边界。 */
final class ScheduleExecutionSafety {

    private ScheduleExecutionSafety() {
    }

    static ScheduledExecutionException pluginFailure(String code) {
        return new ScheduledExecutionException(ScheduledFailure.Category.INTERNAL, code);
    }

    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    /** 先完成租约释放，再决定 fatal 是否必须原样越过插件异常归一边界。 */
    static void closeBeforeFatalPropagation(AutoCloseable lease, Throwable failure) {
        DeferredFatal fatalFailures = new DeferredFatal();
        fatalFailures.capture(failure);
        try {
            lease.close();
        } catch (Throwable closeFailure) {
            if (!fatalFailures.capture(closeFailure) && failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        fatalFailures.rethrowIfPresent();
    }

    static void closeBeforeFatalPropagation(
            AutoCloseable first,
            AutoCloseable second,
            Throwable failure) {
        DeferredFatal fatalFailures = new DeferredFatal();
        fatalFailures.capture(failure);
        closeForPropagation(first, failure, fatalFailures);
        closeForPropagation(second, failure, fatalFailures);
        fatalFailures.rethrowIfPresent();
    }

    private static void closeForPropagation(
            AutoCloseable lease,
            Throwable failure,
            DeferredFatal fatalFailures) {
        try {
            lease.close();
        } catch (Throwable closeFailure) {
            if (!fatalFailures.capture(closeFailure) && failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    static ScheduleExecutionControlException control(
            ScheduledGuardDecision.Action action,
            String code,
            long retryAfterMillis,
            ScheduledGuardEvidence evidence) {
        return new ScheduleExecutionControlException(action, code, retryAfterMillis, evidence);
    }

    static ScheduleExecutionControlException control(
            ScheduledGuardDecision.Action action,
            String code,
            long retryAfterMillis,
            ScheduledGuardEvidence evidence,
            ScheduledCredentialIncidentPresentation incidentPresentation) {
        return new ScheduleExecutionControlException(
                action, code, retryAfterMillis, evidence, incidentPresentation);
    }

    static void rethrow(
            Throwable failure,
            ScheduleCredentialMaterial credential)
            throws ScheduleExecutionControlException, ScheduledExecutionException {
        rethrowFatal(failure);
        if (failure instanceof ScheduleExecutionControlException control) {
            if (credential.containsEcho(control.reasonCode())
                    || containsEcho(control.evidence(), credential)) {
                throw pluginFailure("schedule.execution.invalid-failure-code");
            }
            throw control;
        }
        if (failure instanceof ScheduledExecutionException scheduled) {
            throw safePluginException(
                    scheduled, "schedule.execution.invalid-failure-code", credential);
        }
        throw pluginFailure("schedule.execution.failed");
    }

    static ScheduledExecutionException safePluginException(
            ScheduledExecutionException failure,
            String fallbackCode) {
        return safePluginException(failure, fallbackCode, null);
    }

    static ScheduledExecutionException safePluginException(
            ScheduledExecutionException failure,
            String fallbackCode,
            ScheduleCredentialMaterial credential) {
        if (failure instanceof ScheduleCredentialCircuitOpenException circuitOpen) {
            if (credential != null
                    && (credential.containsEcho(circuitOpen.code())
                    || credential.containsEcho(circuitOpen.lastFailureCode()))) {
                return pluginFailure(fallbackCode);
            }
            return circuitOpen;
        }
        try {
            String code = failure.code();
            if (!isSafeMachineCode(code)
                    || (credential != null && credential.containsEcho(code))) {
                return pluginFailure(fallbackCode);
            }
            return new ScheduledExecutionException(
                    failure.category(), code, failure.retryAfterMillis());
        } catch (Throwable projectionFailure) {
            rethrowFatal(projectionFailure);
            return pluginFailure(fallbackCode);
        }
    }

    static boolean isSafeMachineCode(String code) {
        return code != null
                && code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                && !ScheduleCredentialRedactor.containsCredentialMaterial(code);
    }

    static boolean isSafeAccountKey(String accountKey) {
        return accountKey == null
                || (accountKey.length() <= 256
                && !ScheduleCredentialRedactor.containsCredentialMaterial(accountKey));
    }

    static ScheduledGuardEvidence sanitizeEvidence(
            ScheduledGuardEvidence evidence,
            ScheduleCredentialMaterial credential,
            String fallbackCode) throws ScheduledExecutionException {
        if (containsEcho(evidence, credential)) {
            throw pluginFailure(fallbackCode);
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        evidence.attributes().forEach((key, value) -> sanitized.put(
                key, ScheduleCredentialRedactor.redact(value)));
        return new ScheduledGuardEvidence(sanitized);
    }

    static boolean containsEcho(
            ScheduledGuardEvidence evidence,
            ScheduleCredentialMaterial credential) {
        for (Map.Entry<String, String> entry : evidence.attributes().entrySet()) {
            if (credential.containsEcho(entry.getKey())
                    || credential.containsEcho(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** best-effort 清理继续执行；fatal 在全部清理完成后按首次失败传播。 */
    static final class DeferredFatal {
        private Error first;

        boolean capture(Throwable failure) {
            Error fatal = fatalError(failure);
            if (fatal == null) {
                return false;
            }
            if (first == null) {
                first = fatal;
            } else if (first != fatal) {
                first.addSuppressed(fatal);
            }
            return true;
        }

        boolean hasFailure() {
            return first != null;
        }

        void rethrowIfPresent() {
            if (first != null) {
                throw first;
            }
        }

        private static Error fatalError(Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                return fatal;
            }
            if (failure instanceof ThreadDeath fatal) {
                return fatal;
            }
            return null;
        }
    }
}
