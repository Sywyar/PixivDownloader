package top.sywyar.pixivdownload.plugin.lifecycle;

import java.util.Objects;

/** 在生命周期补偿全部尝试完成前保存主失败，并让 JVM 致命错误保持最高优先级。 */
final class PluginLifecycleFailureAccumulator {

    private Throwable primary;

    PluginLifecycleFailureAccumulator(Throwable failure) {
        this.primary = Objects.requireNonNull(failure, "failure");
    }

    void record(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!isFatal(primary) && isFatal(failure)) {
            Throwable previous = primary;
            primary = failure;
            addSuppressedSafely(primary, previous);
            return;
        }
        addSuppressedSafely(primary, failure);
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

    static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == null || suppressed == null || target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败。
        }
    }

    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }
}
