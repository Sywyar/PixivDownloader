package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.sdk.SdkVersion;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogManifest;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPage;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPageQuery;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogDetailPage;
import top.sywyar.pixivdownload.plugin.market.presentation.PluginCatalogCategory;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogRevocationService;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore.RevocationSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 插件市场后端服务：把 {@code plugin.catalog} 引擎（{@link PluginRepositoryRegistry} 仓库列表 + {@link PluginCatalogService}
 * 清单读取 + {@link PluginCatalogAcquisitionService} 安装编排）投影为市场页可直接消费的只读 DTO，并把<b>按 repositoryId</b>
 * 的安装委托给引擎。
 *
 * <h2>受控标识、绝不接受任意 URL</h2>
 * 所有入口只接受受控标识（{@code repositoryId} / {@code pluginId} / {@code version}），<b>绝不</b>接受任意下载 / 清单 URL：
 * {@code repositoryId} 只能解析服务端已配置仓库列表里的仓库（未知即 {@link PluginCatalogErrorCode#UNKNOWN_REPOSITORY}），
 * 下载地址只来自该仓库受信清单里按 id+version 选出的包。代理策略不支持、仓库禁用、清单失败、未知插件 / 版本等都映射为
 * 稳定错误码（由控制器的 {@code @ExceptionHandler} 解析为本地化响应）。
 *
 * <p>市场元数据只展示 / 检索 / 排序，<b>不</b>参与安装安全决策——安装仍由包的 sha256 / 大小 / 签名（fail-closed）/ 描述符
 * 经既有受信安装链路权威裁定。
 *
 * <h2>安装状态投影（只读、不混入运行期管理）</h2>
 * 本机版本、内容摘要与来源取自安装器同一锁域的磁盘快照；运行版本由 {@link PluginStatusService} 单独提供。
 * 恢复状态或生命周期代次变化时丢弃内容比较，不能让驻留旧实例或旧来源记录代替当前安装包。本服务<b>只读</b>运行状态、
 * <b>绝不</b>暴露 load / start / stop 等运行期动词——那属于插件管理职责，与市场浏览 / 安装正交。
 */
public class PluginMarketService {
    private static final long COMPATIBILITY_SEARCH_BUDGET_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);

    private final PluginRepositoryRegistry repositoryRegistry;
    private final PluginCatalogService catalogService;
    private final PluginCatalogAcquisitionService acquisitionService;
    private final PluginStatusService pluginStatusService;
    private final PluginCatalogRevocationService revocations;
    private top.sywyar.pixivdownload.plugin.catalog.community.CommunityPackageService communityPackages;
    private ExternalPluginInstaller installer;
    private ExternalPluginLifecycleCoordinator lifecycle;

    @Autowired
    public PluginMarketService(PluginRepositoryRegistry repositoryRegistry,
                               PluginCatalogService catalogService,
                               PluginCatalogAcquisitionService acquisitionService,
                               PluginStatusService pluginStatusService,
                               PluginCatalogRevocationService revocations) {
        this.repositoryRegistry = repositoryRegistry;
        this.catalogService = catalogService;
        this.acquisitionService = acquisitionService;
        this.pluginStatusService = pluginStatusService;
        this.revocations = revocations;
    }

    public PluginMarketService(PluginRepositoryRegistry repositoryRegistry,
                               PluginCatalogService catalogService,
                               PluginCatalogAcquisitionService acquisitionService,
                               PluginStatusService pluginStatusService) {
        this(repositoryRegistry, catalogService, acquisitionService, pluginStatusService, null);
    }

    public PluginMarketService(PluginRepositoryRegistry repositoryRegistry, PluginCatalogService catalogService,
                               PluginCatalogAcquisitionService acquisitionService, PluginStatusService pluginStatusService,
                               PluginCatalogRevocationService revocations,
                               top.sywyar.pixivdownload.plugin.catalog.community.CommunityPackageService communityPackages) {
        this(repositoryRegistry, catalogService, acquisitionService, pluginStatusService, revocations);
        this.communityPackages = communityPackages;
    }

    /** 按选择的确切版本读取一份审核记录，避免浏览版本页时批量下载审核文件。 */
    public top.sywyar.pixivdownload.plugin.verification.PluginVerificationView packageFacts(
            String repositoryId, String pluginId, String version) {
        var resolved = catalogService.resolvePackage(repositoryId, pluginId, version);
        var repository = resolved.repository();
        var pkg = resolved.pkg();
        var snapshot = refreshRevocations(repository);
        var view = top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector.forCatalogPackage(repository, pkg);
        var previous = pluginStatusService.report().diagnostics().stream()
                .filter(item -> pluginId.equals(item.id())).map(PluginDiagnostic::descriptor)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String revocation = revocations != null ? revocations.status(repository, pluginId, pkg, snapshot) : "NOT_CHECKED";
        if (revocations != null) view = view.withRevocation(revocations.details(repository, pluginId, pkg, snapshot));
        if (!repository.community()) return view.withFacts(revocation, null, null, previous);
        if (communityPackages == null) throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                "community package verifier is unavailable");
        var review = top.sywyar.pixivdownload.sdk.community.review.CommunityReview.read(
                communityPackages.review(repository, pluginId, pkg));
        return view.withFacts(revocation, review.descriptor().executionMode(), review.descriptor().riskDeclaration(), previous);
    }

    /**
     * 仓库列表 + 主开关状态 + 当前SDK 版本 + 默认仓库 id。主开关关闭时 {@code enabled=false} 但仍列出仓库
     * （供管理员查看 / 决定开启）。
     */
    public PluginMarketRepositoriesView repositories() {
        String defaultId = repositoryRegistry.defaultRepository()
                .map(PluginRepository::repositoryId).orElse(null);
        List<PluginMarketRepositoryView> views = repositoryRegistry.repositories().stream()
                .map(repository -> PluginMarketRepositoryView.from(
                        repository, repository.repositoryId().equals(defaultId)))
                .toList();
        return new PluginMarketRepositoriesView(
                repositoryRegistry.featureEnabled(), SdkVersion.VERSION, defaultId, views);
    }

    /**
     * 指定仓库（{@code repositoryId} 为空时取默认仓库）的 catalog 摘要 + 分类计数。主开关关闭 → {@link PluginMarketView#disabled()}
     * （200 正常「功能未开」）；未知 / 禁用仓库、清单拉取 / 解析失败 → {@link PluginCatalogException}（控制器映射为稳定错误）。
     */
    public PluginMarketView catalog(String repositoryId) {
        return catalog(repositoryId, PluginCatalogPageQuery.first());
    }

    public PluginMarketView catalog(String repositoryId, PluginCatalogPageQuery query) {
        if (!repositoryRegistry.featureEnabled()) {
            return PluginMarketView.disabled();
        }
        PluginRepository repository = resolveRepository(repositoryId);
        query = query == null ? PluginCatalogPageQuery.first() : query;
        RevocationSnapshot snapshot = refreshRevocations(repository);
        PluginCatalogPage page = catalogService.loadPage(repository, query);
        var installed = installedVersionsById();
        long compatibilityDeadline = System.nanoTime() + COMPATIBILITY_SEARCH_BUDGET_NANOS;
        List<PluginMarketEntryView> entries = page.items().stream()
                .map(entry -> selectVersion(repository, entry, installed, snapshot, page.generation(),
                        repository.pagedCatalog() || entry.history() != null, compatibilityDeadline))
                .filter(entry -> entry.packages().isEmpty() || entry.latestVersion() != null)
                .toList();
        var visibleIds = entries.stream().map(PluginMarketEntryView::pluginId).collect(java.util.stream.Collectors.toSet());
        List<PluginCatalogEntry> visible = page.items().stream()
                .filter(entry -> visibleIds.contains(entry.pluginId())).toList();
        int installedCount = (int) entries.stream()
                .filter(entry -> entry.installedVersion() != null)
                .count();
        return new PluginMarketView(repository.repositoryId(), true, SdkVersion.VERSION,
                installedCount, categoryCounts(visible), entries, page.generation(), page.nextCursor(),
                entries.size() == page.items().size() ? page.totalApproximate()
                        : query.cursor() == null && page.nextCursor() == null ? (long) entries.size() : null,
                entries.size() == page.items().size() ? page.facets() : Map.of(), page.stale());
    }

    /**
     * 指定仓库 + 插件 id 的条目详情（含一页有界版本摘要）。主开关关闭 → {@link PluginCatalogErrorCode#CATALOG_DISABLED}；
     * 未知 / 禁用仓库、清单失败、未知插件 id → 对应稳定错误码。
     */
    public PluginMarketEntryView pluginDetail(String repositoryId, String pluginId) {
        return pluginDetail(repositoryId, pluginId, null, 24);
    }

    public PluginMarketEntryView pluginDetail(String repositoryId, String pluginId, String cursor, int limit) {
        PluginRepository repository = resolveRepository(repositoryId);
        RevocationSnapshot snapshot = refreshRevocations(repository);
        PluginCatalogDetailPage page = catalogService.loadEntryPage(
                repository, pluginId, cursor, limit);
        var installed = installedVersionsById();
        var first = cursor == null ? page
                : catalogService.loadEntryPage(repository, pluginId, null, limit);
        var selected = selectVersion(repository, first.item(), installed, snapshot, first.generation(),
                first.nextCursor() != null, System.nanoTime() + COMPATIBILITY_SEARCH_BUDGET_NANOS);
        // 后续历史页只贡献该页版本，不覆盖默认选择；不同代次不混合。
        if (!page.generation().equals(first.generation())) page = first;
        var visible = new LinkedHashMap<String, PluginMarketPackageView>();
        projectEntry(repository, page.item(), installed, snapshot).packages()
                .forEach(pkg -> visible.put(pkg.version(), pkg));
        if (cursor == null) selected.packages().stream()
                .filter(pkg -> pkg.version().equals(selected.recommendedVersion())
                        || pkg.version().equals(selected.latestVersion()))
                .forEach(pkg -> visible.putIfAbsent(pkg.version(), pkg));
        return selected.withPackages(List.copyOf(visible.values()), selected.compatibilitySearchIncomplete())
                .withVersionPage(page.generation(), page.nextCursor(), page.totalApproximate(), page.stale());
    }

    private PluginMarketEntryView selectVersion(PluginRepository repository, PluginCatalogEntry entry,
            InstalledState installed, RevocationSnapshot snapshot, String generation, boolean incomplete,
            long deadlineNanos) {
        var view = projectEntry(repository, entry, installed, snapshot);
        if (!incomplete || view.compatibilityReason() == null && view.recommendedVersion() != null) return view;
        try {
            var complete = catalogService.loadEntrySnapshot(repository, entry.pluginId(), deadlineNanos);
            if (complete.stale() || !generation.equals(complete.generation())) {
                return view.withPackages(view.packages(), true);
            }
            var selected = projectEntry(repository, complete.item(), installed, snapshot);
            // 列表只返回最新与推荐制品；完整历史仍由详情分页按需返回。
            var summary = selected.packages().stream()
                    .filter(pkg -> pkg.version().equals(selected.latestVersion())
                            || pkg.version().equals(selected.recommendedVersion())).toList();
            return selected.withPackages(summary, false);
        } catch (PluginCatalogException failure) {
            return view.withPackages(view.packages(), true);
        }
    }

    /** 据已安装快照把一个 catalog 条目投影为市场视图条目（含安装状态机推导）。 */
    private PluginMarketEntryView projectEntry(PluginRepository repository, PluginCatalogEntry entry,
                                               InstalledState installedVersions, RevocationSnapshot snapshot) {
        var local = installedVersions.entries().getOrDefault(entry.pluginId(), installedVersions.complete()
                ? PluginMarketInstallationView.from(null, null, null, installedVersions.enabled())
                : PluginMarketInstallationView.unknown(null, installedVersions.enabled()));
        boolean installed = local.version() != null;
        var packages = entry.packages().stream().map(pkg -> revocations == null
                ? PluginMarketPackageView.from(repository, pkg)
                : PluginMarketPackageView.from(repository, pkg,
                    revocations.details(repository, entry.pluginId(), pkg, snapshot))).toList();
        return PluginMarketEntryView.from(entry, installed, local.version(), packages).withInstallation(local);
    }

    /** 单次请求复用磁盘清点；来源、字节比较与运行版本各自保留事实归属。 */
    private InstalledState installedVersionsById() {
        long epoch = lifecycle == null ? 0 : lifecycle.lifecycleMutationEpoch();
        var gate = pluginStatusService.recoveryGateSnapshot();
        boolean enabled = installer == null || PluginDevelopmentArtifacts.usesInstalledArtifacts(installer.pluginsDirectory());
        boolean readable = installer != null && gate != null && gate.safeToScan() && (epoch & 1L) == 0L;
        InstalledPluginInventorySnapshot inventory = null;
        if (readable) {
            try {
                inventory = installer.snapshotInstalledWithProvenance(
                        InstalledPluginInventorySnapshot.MAX_RECORDS, InstalledPluginInventorySnapshot.MAX_PROVENANCE_BYTES);
            } catch (IllegalStateException failure) {
                if (gate.equals(pluginStatusService.recoveryGateSnapshot())) throw failure;
            }
        }
        Map<String, PluginMarketInstallationView> versions = new HashMap<>();
        var diagnostics = (installer == null ? pluginStatusService.report() : pluginStatusService.report(
                inventory == null ? List.of() : inventory.entries().stream().map(InstalledPluginSnapshot::plugin).toList())).diagnostics();
        for (PluginDiagnostic diagnostic : diagnostics) {
            if (diagnostic.descriptor() != null) {
                versions.put(diagnostic.id(), PluginMarketInstallationView.unknown(
                        installer == null ? diagnostic.descriptor().version() : null, enabled)
                        .withRuntime(diagnostic.status() == top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.STARTED
                                ? diagnostic.descriptor().version() : null, diagnostic.status().name()));
            }
        }
        if (inventory == null) return new InstalledState(versions, installer == null, enabled);
        if (!gate.equals(pluginStatusService.recoveryGateSnapshot())
                || lifecycle != null && epoch != lifecycle.lifecycleMutationEpoch()) return new InstalledState(versions, false, enabled);
        Map<String, List<InstalledPluginSnapshot>> byId = inventory.entries().stream()
                .collect(java.util.stream.Collectors.groupingBy(item -> item.plugin().id()));
        var ids = new java.util.HashSet<>(versions.keySet());
        ids.addAll(byId.keySet());
        for (String id : ids) {
            var packages = byId.getOrDefault(id, List.of());
            if (packages.size() > 1) {
                versions.put(id, PluginMarketInstallationView.unknown(null, enabled));
                continue;
            }
            var runtime = diagnostics.stream().filter(item -> id.equals(item.id()) && item.descriptor() != null)
                    .findFirst().orElse(null);
            versions.put(id, PluginMarketInstallationView.from(packages.isEmpty() ? null : packages.get(0),
                    runtime != null && runtime.status() == top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.STARTED
                            ? runtime.descriptor().version() : null,
                    runtime != null ? runtime.status().name() : null, enabled));
        }
        return new InstalledState(versions, true, enabled);
    }

    private record InstalledState(Map<String, PluginMarketInstallationView> entries, boolean complete, boolean enabled) { }

    public PluginMarketService(PluginRepositoryRegistry repositories, PluginCatalogService catalog,
            PluginCatalogAcquisitionService acquisition, PluginStatusService status,
            PluginCatalogRevocationService revocations,
            top.sywyar.pixivdownload.plugin.catalog.community.CommunityPackageService communityPackages,
            ExternalPluginInstaller installer, ExternalPluginLifecycleCoordinator lifecycle) {
        this(repositories, catalog, acquisition, status, revocations, communityPackages);
        this.installer = installer;
        this.lifecycle = lifecycle;
    }

    /**
     * 按 {@code repositoryId} + {@code pluginId} + {@code version} 从受信仓库安装。下载地址只来自该仓库清单里选出的包；
     * 安装先下载并校验，再由统一事务编排器替换，是否激活取决于运行模式和生命周期策略。结局由 {@link PluginInstallReport} 承载，
     * catalog 层失败（未知仓库 / 禁用 / 不可用 / 未知插件 / 版本缺失 / 不安全地址等）抛 {@link PluginCatalogException}。
     */
    public PluginInstallReport install(String repositoryId, String pluginId, String version) {
        return install(repositoryId, pluginId, version, null);
    }

    public PluginInstallReport install(String repositoryId, String pluginId, String version,
                                       String confirmedTrustSha256) {
        return acquisitionService.install(repositoryId, pluginId, version, confirmedTrustSha256);
    }

    public top.sywyar.pixivdownload.plugin.catalog.PluginCatalogInstallPreview.View preview(
            String repositoryId, String pluginId, String version) {
        return acquisitionService.preview(repositoryId, pluginId, version);
    }

    public PluginInstallReport installPreviewed(String repositoryId, String pluginId, String version,
            String confirmedTrustSha256, String fingerprint) {
        return acquisitionService.installPreviewed(repositoryId, pluginId, version, confirmedTrustSha256, fingerprint);
    }

    /**
     * 把 {@code repositoryId}（空 → 默认仓库）解析为一个<b>已启用</b>仓库；无可用默认仓库 → {@code CATALOG_DISABLED}、
     * 未知 id → {@code UNKNOWN_REPOSITORY}、目标仓库禁用 → {@code REPOSITORY_DISABLED}。
     */
    private PluginRepository resolveRepository(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            repositoryId = repositoryRegistry.defaultRepository().orElseThrow(() ->
                    new PluginCatalogException(PluginCatalogErrorCode.CATALOG_DISABLED, "no enabled plugin repository")).repositoryId();
        }
        final String selectedId = repositoryId;
        PluginRepository repository = repositoryRegistry.find(repositoryId).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.UNKNOWN_REPOSITORY,
                        "unknown plugin repository: " + selectedId));
        if (!repository.enabled()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DISABLED,
                    "plugin repository is disabled: " + repository.repositoryId());
        }
        return repository.community() ? catalogService.resolveRepository(repository.repositoryId()) : repository;
    }

    /**
     * 分类计数：聚合项 {@code all}（总条目数）在首，随后是全部已知分类（{@link PluginCatalogCategory} 枚举顺序）各自的
     * 条目数（含 0，使页面侧栏拿到完整分类词表 + 派生计数）。每个条目的分类经 {@link PluginCatalogCategory#resolve} 归一化
     * （未知 / 空 → 实用工具回退）。
     */
    private static List<PluginMarketCategoryCount> categoryCounts(PluginCatalogManifest manifest) {
        return categoryCounts(manifest.entries());
    }

    private static List<PluginMarketCategoryCount> categoryCounts(List<PluginCatalogEntry> entries) {
        Map<PluginCatalogCategory, Integer> counts = new LinkedHashMap<>();
        for (PluginCatalogCategory category : PluginCatalogCategory.values()) {
            counts.put(category, 0);
        }
        int total = 0;
        for (PluginCatalogEntry entry : entries) {
            String rawCategory = entry.market() != null ? entry.market().category() : null;
            PluginCatalogCategory category = PluginCatalogCategory.resolve(rawCategory);
            counts.merge(category, 1, Integer::sum);
            total++;
        }
        List<PluginMarketCategoryCount> result = new ArrayList<>();
        result.add(new PluginMarketCategoryCount(PluginCatalogCategory.AGGREGATE_ID, total));
        counts.forEach((category, count) -> result.add(new PluginMarketCategoryCount(category.id(), count)));
        return result;
    }

    /** 受信 catalog / 市场主开关是否开启（供 GUI / 诊断查询，与 {@link #repositories()} 同源）。 */
    public boolean featureEnabled() {
        return repositoryRegistry.featureEnabled();
    }

    private RevocationSnapshot refreshRevocations(PluginRepository repository) {
        return revocations == null ? null : revocations.refreshForBrowsing(repository);
    }

    /** 默认仓库 id（无可用默认仓库时为空）。 */
    public Optional<String> defaultRepositoryId() {
        return repositoryRegistry.defaultRepository().map(PluginRepository::repositoryId);
    }
}
