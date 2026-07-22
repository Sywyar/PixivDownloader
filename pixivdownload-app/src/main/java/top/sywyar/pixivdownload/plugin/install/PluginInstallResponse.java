package top.sywyar.pixivdownload.plugin.install;

import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginDependencyView;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;

import java.util.List;

/**
 * 本地插件包安装 API 响应体：在 {@link PluginInstallReport} 的结构化事实之外，附稳定机器码 {@code outcome}、
 * 本地化 {@code message} 与镜像的 HTTP {@code status}。{@code outcome} 即
 * {@link top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome#name()}（与界面语言无关，供 Web / GUI
 * 管理入口按机器语义分支，不必解析随语言变化的文案），{@code message} 按请求语言解析、<b>绝不</b>作为机器分支依据。
 * HTTP 状态与 i18n 文案 key 均由 {@code outcome} 经 {@link PluginInstallOutcomeMapping} 派生。
 *
 * @param outcome                 结果分类的稳定机器码（{@code INSTALLED} / {@code UPGRADED} / {@code REJECTED_INCOMPATIBLE} / ...）
 * @param accepted                是否最终落盘存在（新装 / 升级 / 降级 / 已存在）
 * @param effectiveAfterRestart   是否存在无法即时激活、需重启确认的兼容结局
 * @param status                  HTTP 状态码（镜像）
 * @param message                 本地化的人类可读说明（按请求语言解析）
 * @param pluginId                安装包的插件 id（描述符不可读时为 {@code null}）
 * @param version                 安装包的版本（描述符不可读时为 {@code null}）
 * @param previousVersion         被取代 / 已存在的旧版本（升级 / 降级 / 重复时在场，否则 {@code null}）
 * @param packageId               物理生命周期包 id（当前发行格式与 {@code pluginId} 相同）
 * @param targetVersion           本次事务目标版本
 * @param operation               包级操作类型
 * @param runtimePhase            响应时的运行阶段
 * @param recoveryBlocked         本次操作是否留下必须先恢复的磁盘事务
 * @param updated                 是否已从旧版本切换到目标版本并激活
 * @param dependencies            描述符声明的插件间依赖投影（依赖诊断）
 * @param unsatisfiedDependencies 当前不可达的非可选依赖 id（机器可读摘要）
 * @param dependencyProblems      未满足依赖的结构化诊断
 * @param diagnostics             安装器英文诊断说明（排错用，非用户文案）
 * @param dependencyInstallResults 本次市场安装过程中自动安装成功的依赖插件结果
 */
public record PluginInstallResponse(
        String outcome,
        boolean accepted,
        boolean effectiveAfterRestart,
        int status,
        String message,
        String pluginId,
        String version,
        String previousVersion,
        String packageId,
        String targetVersion,
        String operation,
        String runtimePhase,
        boolean recoveryBlocked,
        boolean updated,
        List<PluginDependencyView> dependencies,
        List<String> unsatisfiedDependencies,
        List<PluginDependencyProblem> dependencyProblems,
        List<String> diagnostics,
        String transactionId,
        boolean activated,
        boolean rolledBack,
        String rollbackVersion,
        List<PluginDependencyInstallResult> dependencyInstallResults) {
}
