package top.sywyar.pixivdownload.bootstrapprobe;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

/**
 * 端到端重启探针的最小功能插件（仅测试用）：仅声明身份（id {@code bootstrap-probe}、类别 {@link PluginKind#FEATURE}），
 * 不贡献任何 contribution。它存在的唯一目的是让探针包通过 {@code PluginRuntimeManager} 的发布形态校验
 * （一个包恰好贡献一个同 id 功能插件），从而能被真实加载 / 启动。
 *
 * <p>镜像 {@code pixivdownload-plugin-runtime} 测试源的 {@code BootstrapProbeFeaturePlugin}（同一 id、同一最小形态），
 * 非 app 侧的独立生命周期实现——app 测试无法访问 plugin-runtime 的测试类，故在同协议下重新声明同一夹具。
 */
public class BackendRestartProbeFeaturePlugin implements PixivFeaturePlugin {

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
