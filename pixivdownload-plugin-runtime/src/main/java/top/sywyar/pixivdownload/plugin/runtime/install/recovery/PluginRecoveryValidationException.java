package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;

import java.util.Objects;

/** 恢复清单、路径或累计资源校验失败，并保留稳定失败分类。 */
public final class PluginRecoveryValidationException extends Exception {

    private final FailureKind kind;

    public PluginRecoveryValidationException(FailureKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public FailureKind kind() {
        return kind;
    }
}
