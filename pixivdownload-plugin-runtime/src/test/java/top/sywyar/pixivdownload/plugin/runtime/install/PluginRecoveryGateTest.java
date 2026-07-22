package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.Failure;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件事务恢复安全门")
class PluginRecoveryGateTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("并发发布时首个 BLOCKED 结论单调保留且 SAFE 不能覆盖")
    void blockedDecisionIsMonotonic() throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
            PluginTransactionRecoveryReport firstFailure = failedReport(plugins, "first");
            PluginTransactionRecoveryReport laterFailure = failedReport(plugins, "later");
            CountDownLatch start = new CountDownLatch(1);
            Thread first = new Thread(() -> awaitAndBlock(start, installer, firstFailure));
            Thread second = new Thread(() -> awaitAndBlock(start, installer, laterFailure));
            Thread safe = new Thread(() -> awaitAndBlock(
                    start, installer, PluginTransactionRecoveryReport.success()));
            first.start();
            second.start();
            safe.start();
            start.countDown();
            first.join();
            second.join();
            safe.join();

            assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
            assertThat(installer.recoveryGateSnapshot().report().failures()).hasSize(1);
            assertThat(installer.recoveryGateSnapshot().report().failures().get(0).detail())
                    .isIn("first", "later");

            installer.blockRuntimeOperations(PluginTransactionRecoveryReport.success());
            assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
        }
    }

    private static void awaitAndBlock(CountDownLatch start, ExternalPluginInstaller installer,
                                      PluginTransactionRecoveryReport report) {
        try {
            start.await();
            installer.blockRuntimeOperations(report);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static PluginTransactionRecoveryReport failedReport(Path plugins, String detail) {
        return new PluginTransactionRecoveryReport(List.of(new Failure(
                "tx", plugins.resolve(".staging/tx"), FailureKind.RECOVERY_FAILED, detail)));
    }
}
