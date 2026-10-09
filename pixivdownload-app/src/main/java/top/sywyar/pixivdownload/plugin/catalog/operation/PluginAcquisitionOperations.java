package top.sywyar.pixivdownload.plugin.catalog.operation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService;
import top.sywyar.pixivdownload.plugin.catalog.download.PluginCatalogDownloadSession;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyInstallResult;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperationSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 获取期的有界记录与待确认包；实际安装在原请求线程执行，各包事务由协调器持有。 */
@Service
public class PluginAcquisitionOperations implements AutoCloseable {
    static final int MAX_RECORDS = 64;
    static final Duration RETENTION = Duration.ofHours(24);
    static final Duration DOWNLOAD_RETENTION = Duration.ofMinutes(10);
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginAcquisitionOperations.class);
    private final PluginCatalogAcquisitionService acquisition;
    private final ExternalPluginLifecycleCoordinator coordinator;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final java.util.concurrent.ScheduledThreadPoolExecutor expiry = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
        var thread = new Thread(task, "plugin-download-expiry");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public PluginAcquisitionOperations(PluginCatalogAcquisitionService acquisition,
                                       ExternalPluginLifecycleCoordinator coordinator) {
        this(acquisition, coordinator, Clock.systemUTC());
    }

    PluginAcquisitionOperations(PluginCatalogAcquisitionService acquisition,
                                ExternalPluginLifecycleCoordinator coordinator, Clock clock) {
        this.acquisition = acquisition;
        this.coordinator = coordinator;
        this.clock = clock;
        expiry.setRemoveOnCancelPolicy(true);
    }

    /** 先返回查询身份；只有取得此身份的显式执行请求才可能产生安装副作用。 */
    public Snapshot prepare(String repositoryId, String pluginId, String version, String fingerprint, String confirmTrust) {
        return prepare(repositoryId, pluginId, version, fingerprint, confirmTrust, null);
    }

    public Snapshot prepare(String repositoryId, String pluginId, String version, String fingerprint,
                            String confirmTrust, String previousOperationId) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || confirmTrust != null && !confirmTrust.matches("[0-9a-fA-F]{64}")
                || !boundedIdentifier(repositoryId) || !boundedIdentifier(pluginId)
                || version != null && (version.length() > 128 || version.codePoints().anyMatch(Character::isISOControl))) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSTALL_PREVIEW_CHANGED, "invalid preview confirmation");
        }
        // 记录本身不授予安装权限；执行仍在写预约内重新生成并校验完整计划。
        synchronized (this) {
            expire();
            // ponytail: 只有一条实际写链且不排队；需要多仓库并行时再细分预约域。
            if (entries.size() >= MAX_RECORDS) {
                entries.values().stream().filter(value -> !value.started || value.finished).findFirst()
                        .ifPresent(oldest -> { discardDownload(oldest); entries.remove(oldest.id); });
            }
            if (entries.size() >= MAX_RECORDS) {
                throw new PluginCatalogException(PluginCatalogErrorCode.OPERATION_CAPACITY, "operation history is full");
            }
            String id = UUID.randomUUID().toString();
            var entry = new Entry(id, repositoryId, pluginId, version, fingerprint, confirmTrust, clock.instant());
            if (previousOperationId != null) {
                var previous = require(previousOperationId);
                if (!waitingForTrust(previous) || previous.downloads == null
                        || !Objects.equals(repositoryId, previous.repositoryId)
                        || !Objects.equals(pluginId, previous.pluginId) || !Objects.equals(version, previous.version)
                        || !previous.report.trustRequirement().artifactSha256().equalsIgnoreCase(confirmTrust)) {
                    throw new PluginCatalogException(PluginCatalogErrorCode.INSTALL_PREVIEW_CHANGED,
                            "pending artifact confirmation does not match the previous operation");
                }
                entry.downloads = previous.downloads;
                previous.downloads = null;
                cancelExpiry(previous);
                scheduleExpiry(entry);
            }
            entries.put(id, entry);
            return snapshot(entry);
        }
    }

    private static boolean boundedIdentifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    /** 重复调用返回原记录；未知或过期身份不会重新创建执行。客户端断开不取消已开始的安装。 */
    public Snapshot execute(String id) {
        Entry entry;
        synchronized (this) {
            entry = require(id);
            if (entry.started || entry.finished) return snapshot(entry);
            if (entries.values().stream().anyMatch(value -> value.started && !value.finished)) {
                throw new PluginCatalogException(PluginCatalogErrorCode.OPERATION_IN_PROGRESS, "another acquisition is running");
            }
            // 仅保留当前获取链的单包，开始另一次获取即释放其它待确认下载。
            entries.values().stream().filter(value -> value != entry).forEach(this::discardDownload);
            if (entry.downloads == null) entry.downloads = new PluginCatalogDownloadSession();
            cancelExpiry(entry);
            entry.started = true;
            entry.updatedAt = clock.instant();
        }
        try {
            var report = acquisition.installPreviewed(entry.repositoryId, entry.pluginId, entry.version,
                    entry.confirmTrust, entry.fingerprint, progress -> update(entry, progress), entry.downloads);
            synchronized (this) {
                entry.report = report;
                entry.operation = report.accepted() ? ExternalPluginOperation.IDLE : ExternalPluginOperation.FAILED;
                entry.transactionId = report.transactionId();
            }
        } catch (PluginCatalogException failure) {
            synchronized (this) {
                entry.failure = new Failure(failure.code(), failure.pluginId(), failure.version(), failure.dependencyInstallResults());
                entry.operation = ExternalPluginOperation.FAILED;
            }
        } catch (Throwable failure) {
            synchronized (this) {
                entry.failure = new Failure(PluginCatalogErrorCode.OPERATION_FAILED, entry.pluginId, entry.version, List.of());
                entry.operation = ExternalPluginOperation.FAILED;
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            log.error("Plugin acquisition {} failed", id, failure);
        } finally {
            synchronized (this) {
                entry.finished = true;
                entry.updatedAt = clock.instant();
                if (!waitingForTrust(entry)) discardDownload(entry);
                else scheduleExpiry(entry);
            }
        }
        synchronized (this) {
            return snapshot(entry);
        }
    }

    public synchronized Snapshot get(String id) {
        return snapshot(require(id));
    }

    public synchronized List<Snapshot> list() {
        expire();
        return entries.values().stream().map(this::snapshot)
                .sorted(java.util.Comparator.comparing(Snapshot::createdAt).reversed()).toList();
    }

    /** 取消尚未执行的记录或放弃信任确认；不撤销已开始的安装事务。 */
    public synchronized Snapshot discard(String id) {
        var entry = require(id);
        if (entry.started && !entry.finished) {
            throw new PluginCatalogException(PluginCatalogErrorCode.OPERATION_IN_PROGRESS, "acquisition is running");
        }
        discardDownload(entry);
        entry.finished = true;
        return snapshot(entry);
    }

    private boolean waitingForTrust(Entry entry) {
        return entry.finished && entry.report != null
                && entry.report.outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED
                && entry.report.trustRequirement() != null;
    }

    private void discardDownload(Entry entry) {
        cancelExpiry(entry);
        if (entry.downloads != null) entry.downloads.close();
        entry.downloads = null;
    }

    private void cancelExpiry(Entry entry) {
        if (entry.expiry != null) entry.expiry.cancel(false);
        entry.expiry = null;
    }

    private void scheduleExpiry(Entry entry) {
        if (expiry.isShutdown()) { discardDownload(entry); return; }
        entry.expiry = expiry.schedule(() -> {
            synchronized (PluginAcquisitionOperations.this) { discardDownload(entry); }
        }, DOWNLOAD_RETENTION.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @jakarta.annotation.PreDestroy
    @Override
    public synchronized void close() {
        expiry.shutdownNow();
        entries.values().forEach(this::discardDownload);
        entries.clear();
    }

    private synchronized void update(Entry entry, ExternalPluginOperationSnapshot progress) {
        entry.currentPluginId = progress.packageId();
        entry.operation = progress.operation();
        entry.transactionId = progress.transactionId();
        entry.previousTransactionId = coordinator.operation(progress.packageId())
                .map(ExternalPluginOperationSnapshot::transactionId).orElse(null);
        entry.updatedAt = clock.instant();
    }

    private Snapshot snapshot(Entry entry) {
        ExternalPluginOperation operation = entry.operation;
        String transactionId = entry.transactionId;
        if (!entry.finished && transactionId == null && operation == ExternalPluginOperation.INSTALLING) {
            var current = coordinator.operation(entry.currentPluginId).orElse(null);
            if (current != null && !Objects.equals(current.transactionId(), entry.previousTransactionId)) {
                operation = current.operation();
                transactionId = current.transactionId();
            }
        }
        return new Snapshot(entry.id, entry.repositoryId, entry.pluginId, entry.version, entry.currentPluginId,
                operation, transactionId, entry.createdAt, entry.updatedAt, entry.started, entry.finished,
                entry.report, entry.failure);
    }

    private Entry require(String id) {
        expire();
        Entry entry = entries.get(id);
        if (entry == null) throw new PluginCatalogException(PluginCatalogErrorCode.OPERATION_NOT_FOUND,
                "operation expired or belongs to a previous process; inspect installed state before starting a new operation");
        return entry;
    }

    private void expire() {
        Instant cutoff = clock.instant().minus(RETENTION);
        Instant downloadCutoff = clock.instant().minus(DOWNLOAD_RETENTION);
        entries.values().removeIf(entry -> {
            if (entry.started && !entry.finished) return false;
            if (!entry.updatedAt.isAfter(downloadCutoff)) discardDownload(entry);
            if (entry.updatedAt.isAfter(cutoff)) return false;
            discardDownload(entry);
            return true;
        });
    }

    public record Failure(PluginCatalogErrorCode code, String pluginId, String version,
                          List<PluginDependencyInstallResult> dependencyInstallResults) {
        public Failure { dependencyInstallResults = List.copyOf(dependencyInstallResults); }
    }
    public record Snapshot(String id, String repositoryId, String pluginId, String version, String currentPluginId,
            ExternalPluginOperation operation, String transactionId, Instant createdAt, Instant updatedAt,
            boolean started, boolean finished, PluginInstallReport report, Failure failure) { }

    private static final class Entry {
        final String id, repositoryId, pluginId, version, fingerprint, confirmTrust;
        final Instant createdAt;
        Instant updatedAt;
        String currentPluginId, transactionId, previousTransactionId;
        ExternalPluginOperation operation = ExternalPluginOperation.PREPARING;
        boolean started, finished;
        PluginInstallReport report;
        Failure failure;
        PluginCatalogDownloadSession downloads = new PluginCatalogDownloadSession();
        java.util.concurrent.ScheduledFuture<?> expiry;

        Entry(String id, String repositoryId, String pluginId, String version,
              String fingerprint, String confirmTrust, Instant now) {
            this.id = id;
            this.repositoryId = repositoryId;
            this.pluginId = pluginId;
            this.version = version;
            this.fingerprint = fingerprint;
            this.confirmTrust = confirmTrust;
            this.currentPluginId = pluginId;
            this.createdAt = now;
            this.updatedAt = now;
        }
    }
}
