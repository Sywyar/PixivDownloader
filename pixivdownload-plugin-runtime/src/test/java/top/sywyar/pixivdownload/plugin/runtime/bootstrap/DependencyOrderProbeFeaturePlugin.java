package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

/**
 * 可按 PF4J 包 id 动态声明身份的依赖排序探针，仅用于启动扫描回归测试。
 */
public class DependencyOrderProbeFeaturePlugin implements PixivFeaturePlugin {

    private final String id;

    public DependencyOrderProbeFeaturePlugin(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
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
