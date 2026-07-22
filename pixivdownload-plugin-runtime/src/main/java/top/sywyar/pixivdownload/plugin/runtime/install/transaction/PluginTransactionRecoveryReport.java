package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 启动期待处理插件事务的恢复结果。存在任一失败时，调用方不得进入 PF4J 扫描。
 *
 * @param failures 已逐事务隔离收集的失败；空列表表示所有已发现事务均已安全收敛
 */
public record PluginTransactionRecoveryReport(List<Failure> failures) {

    public PluginTransactionRecoveryReport {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
    }

    public static PluginTransactionRecoveryReport success() {
        return new PluginTransactionRecoveryReport(List.of());
    }

    /** 是否已安全处理全部已发现事务，可以继续扫描插件目录。 */
    public boolean safeToScan() {
        return failures.isEmpty();
    }

    /** 单个事务（或暂存根）未能安全收敛的结构化诊断。 */
    public record Failure(String transactionId, Path transactionDirectory, FailureKind kind, String detail) {

        public Failure {
            transactionId = Objects.requireNonNull(transactionId, "transactionId");
            transactionDirectory = Objects.requireNonNull(transactionDirectory, "transactionDirectory")
                    .toAbsolutePath().normalize();
            kind = Objects.requireNonNull(kind, "kind");
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    /** 恢复失败的稳定分类，供 bootstrap 诊断和测试断言使用。 */
    public enum FailureKind {
        STAGING_ROOT_UNSAFE,
        STAGING_ENUMERATION_FAILED,
        INVALID_TRANSACTION_ENTRY,
        MISSING_MANIFEST,
        INVALID_MANIFEST,
        IDENTITY_CONFLICT,
        UNSAFE_PATH,
        RECOVERY_FAILED
    }
}
