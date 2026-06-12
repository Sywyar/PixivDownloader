package top.sywyar.pixivdownload.stats;

import top.sywyar.pixivdownload.plugin.api.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;
import top.sywyar.pixivdownload.plugin.api.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.WebRouteContribution;

import java.util.List;
import java.util.Set;

/**
 * 统计插件：统计仪表盘页面与 {@code /api/stats/**} 只读聚合。
 * <p>
 * 统计仪表盘是管理员专属功能（{@code ADMIN_OR_SOLO}，即 solo 会话用户或 multi 登录管理员），
 * 不得进入 isPublic / 访客邀请白名单——该不变量由路由镜像测试守护。无私有表
 * （statistics 事实表按卸载投影测试归 core）。
 */
public class StatsPlugin implements PixivFeaturePlugin {

    private static final String ID = "stats";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "统计";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 与 AuthFilter 现行硬编码逐条对应：页面与 API 均按 monitor 语义保护（方法不限）。
        return List.of(
                new WebRouteContribution("/pixiv-stats.html", AccessLevel.ADMIN_OR_SOLO, Set.of(), false),
                new WebRouteContribution("/pixiv-stats/**", AccessLevel.ADMIN_OR_SOLO, Set.of(), false),
                new WebRouteContribution("/api/stats/**", AccessLevel.ADMIN_OR_SOLO, Set.of(), false));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(
                ID, "classpath:/static/pixiv-stats/", "/pixiv-stats/", AccessLevel.ADMIN_OR_SOLO));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.stats"));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(new NavigationContribution(
                ID, "nav.label", "/pixiv-stats.html", "chart-bar", AccessLevel.ADMIN_OR_SOLO, 60));
    }
}
