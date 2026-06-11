package top.sywyar.pixivdownload.novel;

import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;

/**
 * 小说插件：小说画廊/详情页面、小说下载与合订、TTS 与 AI 听书入口。
 */
public class NovelPlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "novel";
    }

    @Override
    public String displayName() {
        return "小说";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }
}
