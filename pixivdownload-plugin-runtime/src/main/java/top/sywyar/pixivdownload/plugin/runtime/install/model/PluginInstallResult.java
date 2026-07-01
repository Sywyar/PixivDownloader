package top.sywyar.pixivdownload.plugin.runtime.install.model;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一次外置插件安装尝试的结果（不可变）：结果分类 + 包级描述符 + 落盘路径 + 被取代的旧版本 + 诊断说明。
 * 安装器对所有「已决」结局都返回本对象（不抛出业务异常），并保证拒绝 / 失败时安装目录不残留半成品。
 *
 * @param outcome         结果分类
 * @param descriptor      包级描述符（连描述符都读不出的非法包为 {@code null}）
 * @param installedPath   落盘后的插件包路径（仅 {@link PluginInstallOutcome#accepted()} 时在场，否则 {@code null}）
 * @param previousVersion 被取代 / 已存在的旧版本号（升级 / 降级 / 重复时在场，否则 {@code null}）
 * @param messages        诊断说明（英文，后端日志 / 排错用；非用户可见文案）
 */
public record PluginInstallResult(
        PluginInstallOutcome outcome,
        PluginDescriptor descriptor,
        Path installedPath,
        String previousVersion,
        List<String> messages) {

    public PluginInstallResult {
        Objects.requireNonNull(outcome, "outcome");
        messages = messages != null ? List.copyOf(messages) : List.of();
    }

    /** 插件是否最终落盘存在（委托 {@link PluginInstallOutcome#accepted()}）。 */
    public boolean accepted() {
        return outcome.accepted();
    }

    /** 安装的插件 id（描述符缺失时为 {@code null}）。 */
    public String pluginId() {
        return descriptor != null ? descriptor.id() : null;
    }

    /** 安装的版本（描述符缺失时为 {@code null}）。 */
    public String version() {
        return descriptor != null ? descriptor.version() : null;
    }
}
