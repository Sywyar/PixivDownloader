package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;

/**
 * 下载工作台插件：{@code pixiv-batch} 页面、下载队列、油猴脚本入口与下载执行。
 */
public class DownloadWorkbenchPlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "download-workbench";
    }

    @Override
    public String displayName() {
        return "下载工作台";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }
}
