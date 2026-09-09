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
import java.util.Locale;
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
    private final Map<ArchiveIdentity, VerifiedArchive> archiveInspections = new LinkedHashMap<>();
    private final Map<ArchiveIdentity, VerifiedArchive> previousInspections;

    public PluginRecoveryResourceBudget() {
        previousInspections = Map.of();
    }

    /** 仅供安装清点复用上一轮的成功结构校验；调用方必须重新计算当前文件的完整 SHA-256。 */
    public PluginRecoveryResourceBudget(PluginRecoveryResourceBudget previousInventory) {
        previousInspections = previousInventory == null
                ? Map.of() : Map.copyOf(previousInventory.archiveInspections);
    }

    private record ArchiveIdentity(String sha256, boolean jar, PluginPackageLimits limits) { }

    private record VerifiedArchive(PluginPackageInspection inspection,
                                   PluginPackageVerifier.VerificationUsage usage) { }

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
        ArchiveIdentity identity = new ArchiveIdentity(sha256,
                artifact.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"), limits);
        VerifiedArchive cached = archiveInspections.get(identity);
        if (cached != null) {
            return cached.inspection();
        }
        cached = previousInspections.get(identity);
        if (cached != null) {
            // 复用只省去解压工作，不能省去本轮累计预算或改变单包限制。
            consumeArchiveUsage(cached.usage().entryCount(), cached.usage().totalUncompressedBytes());
            archiveInspections.put(identity, cached);
            return cached.inspection();
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
        archiveInspections.put(identity, new VerifiedArchive(inspection, usage));
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
