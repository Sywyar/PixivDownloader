package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.common.SemanticVersion;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;

import java.util.List;

/**
 * 市场视图中一个插件条目的对外投影（卡片摘要 + 详情共用）。{@code displayNameKey} / {@code descriptionKey} 是 i18n key
 * （前端在对应 namespace 解析；插件已安装时其 i18n 包才解析得出，未安装浏览用 {@link PluginMarketMetaView} 的字面文本兜底）。
 * {@code market} 为净化后的市场展示元数据（可空），{@code packages} 为可安装版本列表（含版本历史 / 兼容标记）。
 *
 * <p><b>安装状态投影</b>（{@code installStatus} / {@code installedVersion} / {@code updateAvailable} / {@code compatible}
 * / {@code compatibilityReason}）由后端把本条目与<b>真实运行时安装状态</b>交叉引用推导，供市场页直接渲染未安装 / 已安装 /
 * 有更新 / 不兼容控件、而<b>不</b>由前端臆测：{@code installStatus} 见 {@link MarketInstallStatus}；{@code compatible} =
 * 最新可安装版本是否被当前核心 API 满足，不兼容时 {@code compatibilityReason} 给出其声明的核心 API 要求（可诊断）；安装只
 * 通过统一事务编排器落盘并即时激活。
 *
 * @param pluginId             插件 id
 * @param displayNameKey       展示名 i18n key（可空）
 * @param descriptionKey       简介 i18n key（可空）
 * @param latestVersion        最新可用版本（取市场元数据声明、否则取首个版本制品；可空）
 * @param market               净化后的市场展示元数据（可空）
 * @param packages             可安装版本制品列表
 * @param installStatus        安装状态机机器码（未安装 / 已安装 / 有更新 / 不兼容）
 * @param installedVersion     本机已安装版本（未安装为 {@code null}）
 * @param updateAvailable      是否存在更高且兼容的可安装版本（仅已安装时可能为真）
 * @param compatible           最新可安装版本是否兼容当前核心 API（无可安装版本时视为兼容）
 * @param compatibilityReason  不兼容时声明的核心 API 版本要求（兼容时为 {@code null}；可诊断）
 */
public record PluginMarketEntryView(
        String pluginId,
        String displayNameKey,
        String descriptionKey,
        String latestVersion,
        PluginMarketMetaView market,
        List<PluginMarketPackageView> packages,
        MarketInstallStatus installStatus,
        String installedVersion,
        boolean updateAvailable,
        boolean compatible,
        String compatibilityReason) {

    public PluginMarketEntryView {
        packages = packages != null ? List.copyOf(packages) : List.of();
    }

    /**
     * 把一个 catalog 条目投影为市场视图条目，并据「是否已安装 + 已安装版本」推导安装状态机。
     *
     * @param entry            catalog 条目
     * @param installed        本机当前是否已安装该插件 id（运行时真实状态）
     * @param installedVersion 已安装版本（未安装 / 版本不可知为 {@code null}）
     */
    static PluginMarketEntryView from(PluginRepository repository, PluginCatalogEntry entry,
                                      boolean installed, String installedVersion) {
        List<PluginMarketPackageView> packages = entry.packages().stream()
                .map(pkg -> PluginMarketPackageView.from(repository, pkg))
                .toList();
        PluginMarketMetaView market = PluginMarketMetaView.from(entry.market());
        String latestVersion = resolveLatestVersion(market, packages);
        PluginMarketPackageView target = installTarget(packages, latestVersion);
        boolean installable = target != null;
        boolean compatible = target == null || target.compatible();
        String compatibilityReason = (target != null && !target.compatible()) ? target.requiredCoreApi() : null;
        // 仅当市场最新版本「严格高于」已安装版本（按 SemanticVersion 语义比较）才算有更新：语义等价版本
        // （如 1.2 与 1.2.0）不提示更新，本机版本更高时也不提示（保持已安装）。
        boolean updateAvailable = installed && compatible && installedVersion != null
                && latestVersion != null
                && SemanticVersion.compare(latestVersion, installedVersion) > 0;
        MarketInstallStatus status = MarketInstallStatus.resolve(installed, installable, updateAvailable, compatible);
        return new PluginMarketEntryView(
                entry.pluginId(),
                entry.displayNameKey(),
                entry.descriptionKey(),
                latestVersion,
                market,
                packages,
                status,
                installed ? installedVersion : null,
                updateAvailable,
                compatible,
                compatibilityReason);
    }

    /** 安装目标版本制品（用于兼容判定）：优先版本号等于 {@code latestVersion} 的包，否则首个包（清单约定新版本在前），都无则 {@code null}。 */
    private static PluginMarketPackageView installTarget(List<PluginMarketPackageView> packages, String latestVersion) {
        if (packages.isEmpty()) {
            return null;
        }
        if (latestVersion != null) {
            for (PluginMarketPackageView pkg : packages) {
                if (latestVersion.equals(pkg.version())) {
                    return pkg;
                }
            }
        }
        return packages.get(0);
    }

    /** 最新版本：优先市场元数据声明的 {@code latestVersion}，否则取首个版本制品的版本（清单约定新版本在前），都无则 {@code null}。 */
    private static String resolveLatestVersion(PluginMarketMetaView market, List<PluginMarketPackageView> packages) {
        if (market != null && market.latestVersion() != null && !market.latestVersion().isBlank()) {
            return market.latestVersion();
        }
        return packages.isEmpty() ? null : packages.get(0).version();
    }
}
