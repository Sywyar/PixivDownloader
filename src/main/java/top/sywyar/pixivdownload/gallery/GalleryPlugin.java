package top.sywyar.pixivdownload.gallery;

import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;

/**
 * 画廊插件：画廊页面、{@code /api/gallery/**} 与批量管理入口。
 */
public class GalleryPlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "gallery";
    }

    @Override
    public String displayName() {
        return "画廊";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }
}
