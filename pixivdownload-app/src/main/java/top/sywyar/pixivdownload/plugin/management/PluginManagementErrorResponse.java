package top.sywyar.pixivdownload.plugin.management;

import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;

/**
 * 插件管理 API 失败响应体：在本地化 {@code message} 之外额外携带稳定机器码 {@code code}，使管理入口（Web / GUI）
 * 能按机器语义分支处理失败，而不必解析随界面语言变化的文案。{@code status} 镜像 HTTP 状态码；
 * {@code pluginId} / {@code action} / {@code runtimePhase} 为可空诊断上下文。
 *
 * @param code         稳定机器码（{@link PluginManagementErrorCode#name()}，与界面语言无关）
 * @param message      本地化的人类可读说明（按请求语言解析）
 * @param status       HTTP 状态码
 * @param pluginId     目标插件 id（可空）
 * @param action       尝试执行的运行期动词 token（状态查询 / 无动词时为空）
 * @param runtimePhase 目标插件当时的运行期阶段（仅在已知时附带，可空）
 */
public record PluginManagementErrorResponse(
        String code,
        String message,
        int status,
        String pluginId,
        String action,
        PluginRuntimePhase runtimePhase) {
}
