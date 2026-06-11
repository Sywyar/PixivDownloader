package top.sywyar.pixivdownload.stats;

import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;

/**
 * 统计插件：统计仪表盘页面与 {@code /api/stats/**} 只读聚合。
 */
public class StatsPlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "stats";
    }

    @Override
    public String displayName() {
        return "统计";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }
}
