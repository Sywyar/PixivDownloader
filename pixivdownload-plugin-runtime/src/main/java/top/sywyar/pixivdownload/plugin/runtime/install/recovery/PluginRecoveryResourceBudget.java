package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一轮插件事务恢复或安装清点共用的累计资源预算与 archive 检视缓存。 */
public final class PluginRecoveryResourceBudget {

    private static final long MAX_MANIFEST_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_BACKUPS = 1_024;
    private static final int MAX_ENTRIES = 8_192;
    private static final long MAX_ARTIFACT_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_SIDECAR_BYTES = 64L * 1024L * 1024L;
    // 一轮恢复会同时复核可见插件集与待安装包，因此预算为启动总量与单包上限之和。
    private static final int MAX_ARCHIVE_ENTRIES = 96_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 1_376L * 1024L * 1024L;

    private long manifestBytes;
    private int backups;
    private int entries;
    private long artifactBytes;
    private long sidecarBytes;
    private int archiveEntries;
    private long uncompressedBytes;
    private boolean exhausted;
    private final Map<String, PluginPackageInspection> archiveInspections = new LinkedHashMap<>();

    public void requireAvailable() throws PluginRecoveryValidationException {
        if (exhausted) {
            throw invalid("recovery cumulative resource budget is already exhausted");
        }
    }

    public boolean exhausted() {
        return exhausted;
    }

    public void consumeManifestBytes(long bytes) throws PluginRecoveryValidationException {
        manifestBytes = boundedAddOrExhaust(manifestBytes, bytes, MAX_MANIFEST_BYTES,
                "recovery manifests exceed the cumulative byte budget");
    }

    public void consumeManifest(int backupCount, long newArtifactBytes, List<Long> backupArtifactBytes)
            throws PluginRecoveryValidationException {
        requireAvailable();
        if (backupCount < 0 || backupCount > MAX_BACKUPS - backups) {
            throw exhaust("recovery backups exceed the cumulative count budget");
        }
        backups += backupCount;
        long declaredBytes = newArtifactBytes;
        for (long backupBytes : backupArtifactBytes) {
            declaredBytes = boundedAddOrExhaust(declaredBytes, backupBytes, MAX_ARTIFACT_BYTES,
                    "recovery artifacts exceed the cumulative byte budget");
        }
        artifactBytes = boundedAddOrExhaust(artifactBytes, declaredBytes, MAX_ARTIFACT_BYTES,
                "recovery artifacts exceed the cumulative byte budget");
    }

    public void consumeEntries(int count) throws PluginRecoveryValidationException {
        requireAvailable();
        if (count < 0 || count > MAX_ENTRIES - entries) {
            throw exhaust("recovery transaction trees exceed the cumulative entry budget");
        }
        entries += count;
    }

    public void consumeSidecarBytes(long bytes) throws PluginRecoveryValidationException {
        sidecarBytes = boundedAddOrExhaust(sidecarBytes, bytes, MAX_SIDECAR_BYTES,
                "recovery provenance exceeds the cumulative byte budget");
    }

    public PluginPackageInspection inspectArchive(Path artifact, String sha256, PluginPackageLimits limits)
            throws PluginRecoveryValidationException {
        requireAvailable();
        PluginPackageInspection cached = archiveInspections.get(sha256);
        if (cached != null) {
            return cached;
        }
        int remainingEntries = MAX_ARCHIVE_ENTRIES - archiveEntries;
        long remainingUncompressed = MAX_UNCOMPRESSED_BYTES - uncompressedBytes;
        if (remainingEntries <= 0 || remainingUncompressed <= 0L) {
            throw exhaust("plugin archives exceed the cumulative recovery verification budget");
        }
        PluginPackageLimits effectiveLimits = new PluginPackageLimits(
                limits.maxArchiveBytes(),
                Math.min(limits.maxEntries(), remainingEntries),
                Math.min(limits.maxTotalUncompressedBytes(), remainingUncompressed),
                Math.min(limits.maxEntryUncompressedBytes(), remainingUncompressed),
                limits.maxDescriptorBytes(),
                limits.maxCompressionRatio(),
                limits.maxEntryNameLength(),
                limits.maxEntryDepth());
        boolean constrainedByRemainingBudget = effectiveLimits.maxEntries() < limits.maxEntries()
                || effectiveLimits.maxTotalUncompressedBytes() < limits.maxTotalUncompressedBytes()
                || effectiveLimits.maxEntryUncompressedBytes() < limits.maxEntryUncompressedBytes();
        PluginPackageVerifier.VerificationUsage usage;
        try {
            usage = PluginPackageVerifier.verifyAndMeasure(artifact, effectiveLimits);
        } catch (PluginPackageException failure) {
            if (failure.hasVerificationUsage()) {
                consumeArchiveUsage(failure.consumedEntries(), failure.consumedUncompressedBytes());
            } else if (constrainedByRemainingBudget) {
                exhausted = true;
            }
            throw failure;
        }
        consumeArchiveUsage(usage.entryCount(), usage.totalUncompressedBytes());
        PluginPackageInspection inspection = PluginPackageReader.inspect(artifact, limits);
        archiveInspections.put(sha256, inspection);
        return inspection;
    }

    private void consumeArchiveUsage(int consumedEntries, long consumedBytes)
            throws PluginRecoveryValidationException {
        if (consumedEntries < 0 || consumedBytes < 0L
                || consumedEntries > MAX_ARCHIVE_ENTRIES - archiveEntries
                || consumedBytes > MAX_UNCOMPRESSED_BYTES - uncompressedBytes) {
            throw exhaust("plugin archives exceed the cumulative recovery verification budget");
        }
        archiveEntries += consumedEntries;
        uncompressedBytes += consumedBytes;
    }

    private long boundedAddOrExhaust(long current, long increment, long maximum, String message)
            throws PluginRecoveryValidationException {
        requireAvailable();
        if (increment < 0L || current > maximum - increment) {
            throw exhaust(message);
        }
        return current + increment;
    }

    private PluginRecoveryValidationException exhaust(String message) {
        exhausted = true;
        return invalid(message);
    }

    private static PluginRecoveryValidationException invalid(String message) {
        return new PluginRecoveryValidationException(FailureKind.INVALID_MANIFEST, message);
    }
}
