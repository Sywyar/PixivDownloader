package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.core.db.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;

import java.util.List;

/**
 * 核心插件：承载核心层（schema、公共静态资源、基础路由等）的 contribution 声明。
 */
public class CorePlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "core";
    }

    @Override
    public String displayName() {
        return "核心";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.CORE;
    }

    @Override
    public List<SchemaContribution> schema() {
        return List.of(CoreSchemaContribution.CONTRIBUTION);
    }
}
