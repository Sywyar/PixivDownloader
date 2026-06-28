package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;

import java.util.List;

/**
 * 市场视图中一个可安装版本制品的对外投影。兼容标记 {@code compatible} 复用既有兼容规则（{@link PluginApiRequirement}，
 * 不另立权威；安装时仍由下载包描述符<b>权威</b>裁定）。正常安装由统一编排器即时激活。
 * 含版本历史展示字段（发布时间 / 更新说明 / 通道 / 是否下架），供详情弹窗与版本列表渲染。
 *
 * @param version               版本
 * @param expectedSizeBytes     期望字节数
 * @param sha256                期望 SHA-256
 * @param signaturePresent      是否声明了签名（非空 → 安装时 fail-closed，当前无校验器即被拒）
 * @param requiredCoreApi       声明的核心 API 版本要求（可空）
 * @param compatible            当前核心 API 是否满足该要求（未声明视为兼容）
 * @param effectiveAfterRestart 安装后是否需重启才生效（当前事务化热更新为 {@code false}）
 * @param dependencies          声明的插件间依赖（展示用）
 * @param releasedTime          发布时间（ISO-8601，可空；版本历史展示）
 * @param changeNotes           更新说明条目（可空；详情弹窗更新日志）
 * @param channel               发布通道（{@code stable} / {@code beta}；{@code null} 视为 stable）
 * @param deprecated            该版本是否已下架 / 不建议（页面可置灰；不阻断安装）
 */
public record PluginMarketPackageView(
        String version,
        long expectedSizeBytes,
        String sha256,
        boolean signaturePresent,
        String requiredCoreApi,
        boolean compatible,
        boolean effectiveAfterRestart,
        List<String> dependencies,
        String releasedTime,
        List<String> changeNotes,
        String channel,
        boolean deprecated) {

    static PluginMarketPackageView from(PluginCatalogPackage pkg) {
        boolean compatible = PluginApiRequirement.parse(pkg.requiredCoreApi()).isSatisfiedByCurrentApi();
        return new PluginMarketPackageView(
                pkg.version(),
                pkg.expectedSizeBytes() != null ? pkg.expectedSizeBytes() : 0L,
                pkg.sha256(),
                pkg.hasSignature(),
                pkg.requiredCoreApi(),
                compatible,
                false,
                pkg.dependencies(),
                pkg.releasedTime(),
                pkg.changeNotes(),
                pkg.channel(),
                pkg.deprecated());
    }
}
