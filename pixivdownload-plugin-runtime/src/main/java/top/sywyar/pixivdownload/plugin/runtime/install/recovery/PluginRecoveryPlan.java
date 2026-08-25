package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryBackup;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryManifest;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryOperation;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionState;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 单个已验证恢复事务的不可变执行计划。 */
public record PluginRecoveryPlan(
        String transactionId,
        Path transaction,
        RecoveryManifest manifest
) {
    public PluginRecoveryPlan {
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        transaction = Objects.requireNonNull(transaction, "transaction");
        manifest = Objects.requireNonNull(manifest, "manifest");
    }

    public boolean finalState() {
        return manifest.state() == PluginTransactionState.ROLLED_BACK
                || manifest.operation() == RecoveryOperation.INSTALL
                && (manifest.state() == PluginTransactionState.ACTIVATED
                || manifest.state() == PluginTransactionState.COMMITTED)
                || manifest.operation() == RecoveryOperation.REMOVE
                && manifest.state() == PluginTransactionState.COMMITTED;
    }

    public boolean requiresVisibleInventory() {
        return manifest.operation() == RecoveryOperation.INSTALL
                && (manifest.state() == PluginTransactionState.ACTIVATED
                || manifest.state() == PluginTransactionState.COMMITTED)
                || manifest.operation() == RecoveryOperation.REMOVE
                && manifest.state() == PluginTransactionState.COMMITTED;
    }

    public Set<String> claimedPluginIdentities() {
        Set<String> identities = new LinkedHashSet<>();
        identities.add(manifest.packageId());
        identities.addAll(manifest.replaces());
        for (RecoveryBackup backup : manifest.backups()) {
            identities.add(backup.expected().pluginId());
        }
        return Collections.unmodifiableSet(identities);
    }
}
