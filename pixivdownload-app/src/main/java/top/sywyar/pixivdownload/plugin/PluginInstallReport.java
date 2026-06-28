package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.PluginManagementService.PluginDependencyView;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;

import java.util.List;

/**
 * 一次本地插件包安装尝试的结构化结果（后端事实，<b>不</b>含 i18n / HTTP）：由 {@link PluginInstallService} 产出，
 * 供 {@link PluginManagementController} 叠加本地化 message 与 HTTP 状态后形成 {@link PluginInstallResponse}。
 *
 * <p>Spring 运行时使用统一编排器完成校验、事务落盘和即时激活；保留的兼容构造器仍可表达只落盘、重启后生效的结果。
 *
 * @param outcome                 结果分类（稳定机器码经 {@link PluginInstallOutcome#name()} 投影到响应）
 * @param accepted                是否最终落盘存在（新装 / 升级 / 降级 / 已存在）
 * @param effectiveAfterRestart   是否存在无法即时激活、需重启确认的兼容结局
 * @param pluginId                安装包的插件 id（描述符不可读的非法包为 {@code null}）
 * @param version                 安装包的版本（描述符不可读时为 {@code null}）
 * @param previousVersion         被取代 / 已存在的旧版本（升级 / 降级 / 重复时在场，否则 {@code null}）
 * @param dependencies            描述符声明的插件间依赖投影（依赖诊断；描述符不可读时为空列表）
 * @param unsatisfiedDependencies 当前不可达的<b>非可选</b>依赖 id（建议性诊断：既非内置、也未在 {@code plugins/} 安装；
 *                                不阻断安装，由插件框架加载期解析）
 * @param diagnostics             安装器英文诊断说明（排错用，非用户文案）
 */
public record PluginInstallReport(
        PluginInstallOutcome outcome,
        boolean accepted,
        boolean effectiveAfterRestart,
        String pluginId,
        String version,
        String previousVersion,
        List<PluginDependencyView> dependencies,
        List<String> unsatisfiedDependencies,
        List<String> diagnostics,
        String transactionId,
        boolean activated,
        boolean rolledBack,
        String rollbackVersion,
        ExternalPluginOperation operation,
        PluginRuntimePhase runtimePhase,
        boolean updated) {

    public PluginInstallReport {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        unsatisfiedDependencies = unsatisfiedDependencies != null ? List.copyOf(unsatisfiedDependencies) : List.of();
        diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
    }

    public PluginInstallReport(PluginInstallOutcome outcome, boolean accepted, boolean effectiveAfterRestart,
                               String pluginId, String version, String previousVersion,
                               List<PluginDependencyView> dependencies, List<String> unsatisfiedDependencies,
                               List<String> diagnostics) {
        this(outcome, accepted, effectiveAfterRestart, pluginId, version, previousVersion, dependencies,
                unsatisfiedDependencies, diagnostics, null, false, false, null,
                ExternalPluginOperation.IDLE, null, false);
    }
}
