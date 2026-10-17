package top.sywyar.pixivdownload.plugin.lifecycle;

import static top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleFailures.mergeFailure;

import java.util.Objects;

/** 在生命周期补偿全部尝试完成前保存主失败，并让 JVM 致命错误保持最高优先级。 */
final class PluginLifecycleFailureAccumulator {

    private Throwable primary;

    PluginLifecycleFailureAccumulator(Throwable failure) {
        this.primary = Objects.requireNonNull(failure, "failure");
    }

    void record(Throwable failure) {
        primary = mergeFailure(primary, Objects.requireNonNull(failure, "failure"));
    }

    Throwable primary() {
        return primary;
    }

    RuntimeException propagate(String message) {
        rethrowFatal();
        if (primary instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        return new PluginLifecycleException(message + " (failureType="
                + primary.getClass().getName() + ")", primary);
    }

    void rethrowFatal() {
        if (primary instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (primary instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }
}
