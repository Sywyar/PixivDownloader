package top.sywyar.pixivdownload.plugin.management;

import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.common.SemanticVersion;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPageQuery;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.market.PluginMarketService;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 市场提供导航与插件管理共用的更新事实，只匹配安装记录中已验证的来源仓库。 */
@PluginManagedBean
public class PluginUpdateService {
    private static final long CACHE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long FAILURE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long LOOKUP_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final PluginRepositoryRegistry repositories;
    private final PluginMarketService market;
    private final ExternalPluginInstaller installer;
    private final ExternalPluginLifecycleCoordinator lifecycle;
    private final ReentrantLock lookup = new ReentrantLock();
    private volatile Cached cached;

    public PluginUpdateService(PluginRepositoryRegistry repositories, PluginMarketService market,
            ExternalPluginInstaller installer, ExternalPluginLifecycleCoordinator lifecycle) {
        this.repositories = repositories;
        this.market = market;
        this.installer = installer;
        this.lifecycle = lifecycle;
    }

    public Summary summary() {
        if (!repositories.featureEnabled()) return new Summary(false, 0, 0, false, null, List.of());
        long epoch = lifecycle.lifecycleMutationEpoch();
        List<PluginRepository> enabled = repositories.enabledRepositories();
        Cached previous = cached;
        if ((epoch & 1L) == 0 && previous != null && previous.epoch == epoch && previous.repositories.equals(enabled)
                && System.nanoTime() - previous.created < (previous.summary.checkFailed ? FAILURE_CACHE_NANOS : CACHE_NANOS)) {
            return previous.summary;
        }
        if ((epoch & 1L) != 0 || !lookup.tryLock()) return unavailable();
        try {
            Summary result = collect(enabled);
            if (epoch != lifecycle.lifecycleMutationEpoch()) return unavailable();
            cached = new Cached(epoch, System.nanoTime(), enabled, result);
            return result;
        } catch (RuntimeException failure) {
            Summary result = unavailable();
            cached = new Cached(epoch, System.nanoTime(), enabled, result);
            return result;
        } finally { lookup.unlock(); }
    }

    private Summary collect(List<PluginRepository> enabled) {
        var inventory = installer.snapshotInstalledWithProvenance(InstalledPluginInventorySnapshot.MAX_RECORDS,
                InstalledPluginInventorySnapshot.MAX_PROVENANCE_BYTES);
        Map<String, InstalledPluginSnapshot> installed = new HashMap<>();
        var duplicates = new HashSet<String>();
        for (var item : inventory.entries()) {
            if (installed.putIfAbsent(item.plugin().id(), item) != null) duplicates.add(item.plugin().id());
        }
        duplicates.forEach(installed::remove);
        List<Update> updates = new ArrayList<>();
        boolean failed = inventory.budgetExhausted() || !duplicates.isEmpty();
        long deadline = System.nanoTime() + LOOKUP_NANOS;
        for (PluginRepository repository : enabled) {
            Map<String, InstalledPluginSnapshot> matching = new HashMap<>();
            installed.forEach((id, item) -> {
                var provenance = item.provenance();
                boolean sameSource = provenance != null && (
                        repository.repositoryId().equals(provenance.repositoryId())
                            && repository.official() == provenance.officialRepository()
                        // 本地有签名包只由内置官方信任根验签；安装器随包分发也使用此来源。
                        || repository.official() && provenance.source() == PluginPackageSource.LOCAL_UPLOAD
                            && provenance.signature() != null);
                if (sameSource
                        && provenance.status() == VerificationStatus.VERIFIED
                        && (provenance.offlineStatus() == null || provenance.offlineStatus() == VerificationStatus.VERIFIED)
                        && item.artifactSha256().equals(provenance.artifactSha256())
                        && item.artifactSizeBytes() == provenance.artifactSizeBytes()) matching.put(id, item);
            });
            if (matching.isEmpty()) continue;
            String cursor = null;
            String generation = null;
            List<Update> repositoryUpdates = new ArrayList<>();
            try {
                for (int page = 0; page < 10; page++) {
                    if (System.nanoTime() - deadline >= 0) throw new IllegalStateException("UPDATE_LOOKUP_TIMEOUT");
                    var catalog = market.catalog(repository.repositoryId(), new PluginCatalogPageQuery(cursor, 100, null, null, null, null));
                    if (catalog.stale() || generation != null && !generation.equals(catalog.generation())) {
                        throw new IllegalStateException("UPDATE_CATALOG_CHANGED");
                    }
                    generation = catalog.generation();
                    for (var entry : catalog.entries()) {
                        var item = matching.remove(entry.pluginId());
                        if (item == null) continue;
                        if (entry.compatibilitySearchIncomplete() || entry.versionsStale()) { failed = true; continue; }
                        String version = item.plugin().version();
                        boolean compatible = entry.recommendedVersion() != null
                                && SemanticVersion.compare(entry.recommendedVersion(), version) > 0;
                        boolean sdkBlocked = entry.compatibilityReason() != null && SemanticVersion.compare(entry.latestVersion(), version) > 0;
                        if (compatible || sdkBlocked) repositoryUpdates.add(new Update(entry.pluginId(), repository.repositoryId(), version,
                                compatible ? entry.recommendedVersion() : null, entry.latestVersion(), entry.compatibilityReason(), compatible, sdkBlocked));
                    }
                    cursor = catalog.nextCursor();
                    if (cursor == null || matching.isEmpty()) break;
                }
                if (cursor != null && !matching.isEmpty()) failed = true;
                updates.addAll(repositoryUpdates);
            } catch (RuntimeException failure) { failed = true; }
        }
        return new Summary(true, (int) updates.stream().filter(Update::compatible).count(),
                (int) updates.stream().filter(Update::sdkBlocked).count(), failed, Instant.now().toString(), List.copyOf(updates));
    }

    private static Summary unavailable() { return new Summary(true, 0, 0, true, null, List.of()); }

    public record Update(String pluginId, String repositoryId, String installedVersion, String compatibleVersion,
                         String latestVersion, String requiredSdk, boolean compatible, boolean sdkBlocked) { }
    public record Summary(boolean enabled, int compatibleUpdates, int sdkBlockedUpdates, boolean checkFailed,
                          String checkedAt, List<Update> updates) { }
    private record Cached(long epoch, long created, List<PluginRepository> repositories, Summary summary) { }
}
