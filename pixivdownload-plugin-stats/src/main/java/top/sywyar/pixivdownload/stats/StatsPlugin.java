package top.sywyar.pixivdownload.stats;

import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

/**
 * 统计插件：统计仪表盘页面与 {@code /api/stats/**} 只读聚合。
 * <p>
 * 统计仪表盘是管理员专属功能（{@code ADMIN}，即 solo 会话用户或 multi 登录管理员），
 * 不得进入 isPublic / 访客邀请白名单——该不变量由路由镜像测试守护。无私有表
 * （statistics 事实表按卸载投影测试归 core）。
 */
public class StatsPlugin implements PixivFeaturePlugin {

    private static final String ID = "stats";

    @Override
    public String id() {
        return ID;
    }

    // 展示名 / 简介在本插件自有 namespace（stats）解析：名称复用已有的导航标签 nav.label，简介用专用 key。
    @Override
    public String displayName() {
        return "nav.label";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 管理员专属：页面与 API 均按 monitor 语义保护（方法不限）。
        return List.of(
                WebRouteContribution.admin("/pixiv-stats.html"),
                WebRouteContribution.admin("/pixiv-stats/**"),
                WebRouteContribution.admin("/api/stats/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(
                ID, "classpath:/static/pixiv-stats/", "/pixiv-stats/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 第三参为 /api/i18n/meta 的全局展示顺序（保持历史 namespace 顺序）。
        return List.of(new I18nContribution(ID, "i18n.web.stats", 7));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 统计入口：管理员可见（ADMIN）。placement——顶部栏 + 各侧栏主导航（含中立主侧栏 app.sidebar，即统计页
        // 自己的侧栏所用 slot），并兼任疑似重复页顶部的统计图标（duplicates.header-icons，与画廊图标并列；该 slot 的
        // 链接样式由页面 slot 的 link-class 决定，无需另立贡献）。priority 50：位于功能页面区段（画廊 30 < 统计 50 < 疑似重复 60）。
        return List.of(new NavigationContribution(
                ID,
                Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
                        NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR,
                        NavigationPlacements.DUPLICATES_HEADER_ICONS),
                "stats:nav.label", "/pixiv-stats.html", "chart-bar", AccessPolicy.ADMIN, 50));
    }
}
