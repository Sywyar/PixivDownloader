package top.sywyar.pixivdownload.plugin.catalog;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;

import java.util.List;

/**
 * 受信 catalog 中一个可安装版本包的对外摘要（GET /api/plugins/catalog 用）。{@code compatible} 复用既有兼容规则
 * （{@link PluginApiRequirement}，不另立权威；安装时仍由下载包描述符<b>权威</b>裁定）。{@code effectiveAfterRestart} 恒
 * {@code true}：安装只落盘、重启后才加载。<b>不含</b>评分 / 下载量 / 作者 / 标签等市场字段（不在最小后端闭环范围）。
 *
 * @param version               版本
 * @param expectedSizeBytes     期望字节数
 * @param sha256                期望 SHA-256
 * @param signaturePresent      是否声明了签名（非空 → 安装时 fail-closed，当前无校验器即被拒）
 * @param requiredCoreApi       声明的核心 API 版本要求（可空）
 * @param compatible            当前核心 API 是否满足该要求（未声明视为兼容）
 * @param effectiveAfterRestart 安装后是否需重启才生效（恒 {@code true}）
 * @param dependencies          声明的插件间依赖（展示用）
 */
public record PluginCatalogPackageView(
        String version,
        long expectedSizeBytes,
        String sha256,
        boolean signaturePresent,
        String requiredCoreApi,
        boolean compatible,
        boolean effectiveAfterRestart,
        List<String> dependencies) {

    static PluginCatalogPackageView from(PluginCatalogPackage pkg) {
        boolean compatible = PluginApiRequirement.parse(pkg.requiredCoreApi()).isSatisfiedByCurrentApi();
        return new PluginCatalogPackageView(
                pkg.version(),
                pkg.expectedSizeBytes() != null ? pkg.expectedSizeBytes() : 0L,
                pkg.sha256(),
                pkg.hasSignature(),
                pkg.requiredCoreApi(),
                compatible,
                true,
                pkg.dependencies());
    }
}
