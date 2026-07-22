package top.sywyar.pixivdownload.plugin.provenance;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;

import java.util.List;

/** 可由测试 classloader 以指定 CodeSource 独立定义的顶层插件夹具。 */
public final class OwnerOnlyAssetPlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "owner-only-asset";
    }

    @Override
    public String displayName() {
        return "owner-only-asset.name";
    }

    @Override
    public String description() {
        return "owner-only-asset.summary";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(
                "classpath:/test-download/", "/owner-only/"));
    }
}
