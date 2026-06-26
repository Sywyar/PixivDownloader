package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogManifest;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogCategory;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;

import java.util.ArrayList;
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
 */
public class PluginMarketService {

    private final PluginRepositoryRegistry repositoryRegistry;
    private final PluginCatalogService catalogService;
    private final PluginCatalogAcquisitionService acquisitionService;

    public PluginMarketService(PluginRepositoryRegistry repositoryRegistry,
                               PluginCatalogService catalogService,
                               PluginCatalogAcquisitionService acquisitionService) {
        this.repositoryRegistry = repositoryRegistry;
        this.catalogService = catalogService;
        this.acquisitionService = acquisitionService;
    }

    /**
     * 仓库列表 + 主开关状态 + 当前核心 API 版本 + 默认仓库 id。主开关关闭时 {@code enabled=false} 但仍列出仓库
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
                repositoryRegistry.featureEnabled(), PluginApiVersion.VERSION, defaultId, views);
    }

    /**
     * 指定仓库（{@code repositoryId} 为空时取默认仓库）的 catalog 摘要 + 分类计数。主开关关闭 → {@link PluginMarketView#disabled()}
     * （200 正常「功能未开」）；未知 / 禁用仓库、清单拉取 / 解析失败 → {@link PluginCatalogException}（控制器映射为稳定错误）。
     */
    public PluginMarketView catalog(String repositoryId) {
        if (!repositoryRegistry.featureEnabled()) {
            return PluginMarketView.disabled();
        }
        PluginRepository repository = resolveRepository(repositoryId);
        PluginCatalogManifest manifest = catalogService.load(repository.repositoryId());
        List<PluginMarketEntryView> entries = manifest.entries().stream()
                .map(PluginMarketEntryView::from)
                .toList();
        return new PluginMarketView(repository.repositoryId(), true, PluginApiVersion.VERSION,
                categoryCounts(manifest), entries);
    }

    /**
     * 指定仓库 + 插件 id 的条目详情（含全部版本历史）。主开关关闭 → {@link PluginCatalogErrorCode#CATALOG_DISABLED}；
     * 未知 / 禁用仓库、清单失败、未知插件 id → 对应稳定错误码。
     */
    public PluginMarketEntryView pluginDetail(String repositoryId, String pluginId) {
        PluginRepository repository = resolveRepository(repositoryId);
        PluginCatalogManifest manifest = catalogService.load(repository.repositoryId());
        PluginCatalogEntry entry = manifest.findEntry(pluginId).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.UNKNOWN_PLUGIN, pluginId, null,
                        "plugin not found in catalog: " + pluginId));
        return PluginMarketEntryView.from(entry);
    }

    /**
     * 按 {@code repositoryId} + {@code pluginId} + {@code version} 从受信仓库安装。下载地址只来自该仓库清单里选出的包；
     * 安装只下载 + 校验 + 落盘，重启后生效（不热加载）。下载 / 校验 / 安装结局由 {@link PluginInstallReport} 承载，
     * catalog 层失败（未知仓库 / 禁用 / 不可用 / 未知插件 / 版本缺失 / 不安全地址等）抛 {@link PluginCatalogException}。
     */
    public PluginInstallReport install(String repositoryId, String pluginId, String version) {
        return acquisitionService.install(repositoryId, pluginId, version);
    }

    /**
     * 把 {@code repositoryId}（空 → 默认仓库）解析为一个<b>已启用</b>仓库；无可用默认仓库 → {@code CATALOG_DISABLED}、
     * 未知 id → {@code UNKNOWN_REPOSITORY}、目标仓库禁用 → {@code REPOSITORY_DISABLED}。
     */
    private PluginRepository resolveRepository(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return repositoryRegistry.defaultRepository().orElseThrow(() ->
                    new PluginCatalogException(PluginCatalogErrorCode.CATALOG_DISABLED, "no enabled plugin repository"));
        }
        PluginRepository repository = repositoryRegistry.find(repositoryId).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.UNKNOWN_REPOSITORY,
                        "unknown plugin repository: " + repositoryId));
        if (!repository.enabled()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DISABLED,
                    "plugin repository is disabled: " + repository.repositoryId());
        }
        return repository;
    }

    /**
     * 分类计数：聚合项 {@code all}（总条目数）在首，随后是全部已知分类（{@link PluginCatalogCategory} 枚举顺序）各自的
     * 条目数（含 0，使页面侧栏拿到完整分类词表 + 派生计数）。每个条目的分类经 {@link PluginCatalogCategory#resolve} 归一化
     * （未知 / 空 → 实用工具回退）。
     */
    private static List<PluginMarketCategoryCount> categoryCounts(PluginCatalogManifest manifest) {
        Map<PluginCatalogCategory, Integer> counts = new LinkedHashMap<>();
        for (PluginCatalogCategory category : PluginCatalogCategory.values()) {
            counts.put(category, 0);
        }
        int total = 0;
        for (PluginCatalogEntry entry : manifest.entries()) {
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

    /** 默认仓库 id（无可用默认仓库时为空）。 */
    public Optional<String> defaultRepositoryId() {
        return repositoryRegistry.defaultRepository().map(PluginRepository::repositoryId);
    }
}
