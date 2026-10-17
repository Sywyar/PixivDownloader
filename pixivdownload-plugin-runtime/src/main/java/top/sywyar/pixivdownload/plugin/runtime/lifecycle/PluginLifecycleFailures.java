package top.sywyar.pixivdownload.plugin.runtime.lifecycle;

/** 生命周期清理保留首个致命错误，其余失败仅附加诊断；异常仍由调用方持有和重抛。 */
public final class PluginLifecycleFailures {

    private PluginLifecycleFailures() {
    }

    public static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (failure == null) {
            return current;
        }
        if (current == null) {
            return failure;
        }
        if (!isFatal(current) && isFatal(failure)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        addSuppressedSafely(current, failure);
        return current;
    }

    public static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    public static void addSuppressedSafely(Throwable target, Throwable failure) {
        if (target == null || failure == null || target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }
}
