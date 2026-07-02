package top.sywyar.pixivdownload.plugin.catalog;

import top.sywyar.pixivdownload.plugin.install.PluginDependencyInstallResult;

import java.util.List;

/**
 * 受信 catalog API 失败响应体：在本地化 {@code message} 之外携带稳定机器码 {@code code}，使管理入口按机器语义分支处理失败，
 * 不必解析随界面语言变化的文案。{@code status} 镜像 HTTP 状态码；{@code pluginId} / {@code version} 为可空诊断上下文。
 *
 * @param code     稳定机器码（{@link PluginCatalogErrorCode#name()}，与界面语言无关）
 * @param message  本地化的人类可读说明（按请求语言解析）
 * @param status   HTTP 状态码
 * @param pluginId 目标插件 id（可空）
 * @param version  目标版本（可空）
 * @param dependencyInstallResults 本次市场安装过程中已经自动安装成功的依赖插件结果
 */
public record PluginCatalogErrorResponse(
        String code,
        String message,
        int status,
        String pluginId,
        String version,
        List<PluginDependencyInstallResult> dependencyInstallResults) {

    public PluginCatalogErrorResponse {
        dependencyInstallResults = dependencyInstallResults != null
                ? List.copyOf(dependencyInstallResults) : List.of();
    }
}
