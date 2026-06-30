package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

/**
 * 探针的最小功能插件（仅测试用）：仅声明身份（id {@code bootstrap-probe}、类别 {@link PluginKind#FEATURE}），
 * 不贡献任何 contribution（路由 / 静态 / i18n / 导航 / schema 等全部沿用接口默认空实现）。它存在的唯一目的是让探针
 * 包通过 {@code PluginRuntimeManager} 的发布形态校验（一个包恰好贡献一个同 id 功能插件），从而能被真实加载 / 启动。
 */
public class BootstrapProbeFeaturePlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "bootstrap-probe";
    }

    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }
}
