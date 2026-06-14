package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;

import java.util.List;

/**
 * 下载工作台插件：{@code pixiv-batch} 页面、下载队列、油猴脚本入口与下载执行。
 */
public class DownloadWorkbenchPlugin implements PixivFeaturePlugin {

    private static final String ID = "download-workbench";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "下载工作台";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<I18nContribution> i18n() {
        // 页面跟插件走：下载工作台页面（batch）与油猴脚本分发文案（userscript）归本插件。
        // 第三参为 /api/i18n/meta 的全局展示顺序（保持历史 namespace 顺序）。
        return List.of(
                new I18nContribution("batch", "i18n.web.batch", 5),
                new I18nContribution("userscript", "i18n.web.userscript", 16));
    }

    @Override
    public List<UserscriptContribution> userscripts() {
        // 油猴脚本分发归下载工作台：ScriptRegistry 经声明方 ClassLoader 扫描此模式，
        // 不再做全局 classpath 扫描假设（物理拆分为插件 jar 后脚本随插件 ClassLoader 解析）。
        return List.of(new UserscriptContribution(ID, "classpath:/static/userscripts/*.user.js"));
    }
}
