package top.sywyar.pixivdownload.duplicate;

import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;

/**
 * 重复检测插件：基于核心图片 Hash 索引的疑似重复页面、API 与手动重扫入口。
 * Hash 的下载后即时计算与缺失回填属核心资产索引能力，不随本插件禁用。
 */
public class DuplicatePlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "duplicate";
    }

    @Override
    public String displayName() {
        return "重复检测";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }
}
