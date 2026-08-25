package top.sywyar.pixivdownload.plugin.runtime.artifact;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

/** 统一约束一次启动扫描消耗的归档验证与 provenance 读取资源。 */
public final class PluginStartupResourceBudget {

    private final int maximumVerificationEntries;
    private final long maximumVerificationBytes;
    private final long maximumProvenanceBytes;
    private long consumedVerificationEntries;
    private long consumedVerificationBytes;
    private long consumedProvenanceBytes;
    private boolean verificationExhausted;
    private boolean provenanceExhausted;

    public PluginStartupResourceBudget(
            int maximumVerificationEntries,
            long maximumVerificationBytes,
            long maximumProvenanceBytes) {
        this.maximumVerificationEntries = maximumVerificationEntries;
        this.maximumVerificationBytes = maximumVerificationBytes;
        this.maximumProvenanceBytes = maximumProvenanceBytes;
    }

    public PluginPackageLimits remainingVerificationLimits(PluginPackageLimits packageLimits) {
        if (verificationExhausted()) {
            throw verificationBudgetExceeded(null);
        }
        int remainingEntries = (int) Math.min(
                packageLimits.maxEntries(),
                maximumVerificationEntries - consumedVerificationEntries);
        long remainingBytes = Math.min(
                packageLimits.maxTotalUncompressedBytes(),
                maximumVerificationBytes - consumedVerificationBytes);
        return new PluginPackageLimits(
                packageLimits.maxArchiveBytes(),
                remainingEntries,
                remainingBytes,
                Math.min(packageLimits.maxEntryUncompressedBytes(), remainingBytes),
                packageLimits.maxDescriptorBytes(),
                packageLimits.maxCompressionRatio());
    }

    public void consumeVerification(PluginPackageVerifier.VerificationUsage usage) {
        consumeVerification(usage, null);
    }

    public void consumeVerificationFailure(PluginPackageException failure) {
        if (!failure.hasVerificationUsage()) {
            verificationExhausted = true;
            return;
        }
        consumeVerification(new PluginPackageVerifier.VerificationUsage(
                failure.consumedEntries(),
                failure.consumedUncompressedBytes()), failure);
    }

    public boolean verificationExhausted() {
        return verificationExhausted;
    }

    public long remainingProvenanceBytes() {
        if (provenanceExhausted()) {
            throw provenanceBudgetExceeded(null);
        }
        return maximumProvenanceBytes - consumedProvenanceBytes;
    }

    public void consumeProvenance(long byteCount) {
        consumeProvenance(byteCount, null);
    }

    public void consumeProvenanceFailure(long byteCount, Throwable cause) {
        consumeProvenance(byteCount, cause);
    }

    public boolean provenanceExhausted() {
        return provenanceExhausted;
    }

    private void consumeVerification(
            PluginPackageVerifier.VerificationUsage usage,
            Throwable cause) {
        boolean exceedsBudget = wouldExceed(
                consumedVerificationEntries,
                usage.entryCount(),
                maximumVerificationEntries)
                || wouldExceed(
                consumedVerificationBytes,
                usage.totalUncompressedBytes(),
                maximumVerificationBytes);
        consumedVerificationEntries += usage.entryCount();
        consumedVerificationBytes += usage.totalUncompressedBytes();
        verificationExhausted = verificationExhausted
                || exceedsBudget
                || consumedVerificationEntries >= maximumVerificationEntries
                || consumedVerificationBytes >= maximumVerificationBytes;
        if (exceedsBudget) {
            throw verificationBudgetExceeded(cause);
        }
    }

    private void consumeProvenance(long byteCount, Throwable cause) {
        boolean exceedsBudget = wouldExceed(
                consumedProvenanceBytes,
                byteCount,
                maximumProvenanceBytes);
        if (byteCount < 0L || consumedProvenanceBytes > Long.MAX_VALUE - byteCount) {
            consumedProvenanceBytes = Long.MAX_VALUE;
        } else {
            consumedProvenanceBytes += byteCount;
        }
        provenanceExhausted = provenanceExhausted
                || exceedsBudget
                || consumedProvenanceBytes >= maximumProvenanceBytes;
        if (exceedsBudget) {
            throw provenanceBudgetExceeded(cause);
        }
    }

    private static boolean wouldExceed(long consumed, long additional, long maximum) {
        return additional < 0L
                || consumed < 0L
                || consumed > maximum
                || additional > maximum - consumed;
    }

    private static PluginRuntimeOperationException verificationBudgetExceeded(Throwable cause) {
        String message = "startup plugin verification cumulative resource budget exceeded";
        return cause == null
                ? new PluginRuntimeOperationException(message)
                : new PluginRuntimeOperationException(message, cause);
    }

    private static PluginRuntimeOperationException provenanceBudgetExceeded(Throwable cause) {
        String message = "startup plugin provenance sidecar cumulative byte budget exceeded";
        return cause == null
                ? new PluginRuntimeOperationException(message)
                : new PluginRuntimeOperationException(message, cause);
    }
}
