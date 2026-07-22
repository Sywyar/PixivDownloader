package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/**
 * 插件市场插件：把受信仓库 catalog 的浏览 / 安装收口为一个独立内置功能插件，向核心贡献 admin-only 的市场页面、
 * 后端 API（{@code /api/plugin-market/**}）、页面静态资源、i18n 与插件页内入口。catalog 引擎（仓库注册中心 / SSRF
 * 安全客户端 / 清单解析 / 下载安装编排）仍住 {@code plugin.catalog} 领域包；本插件只承载「按插件存在」的市场 web 面，
 * 经正向 {@code market → catalog} 依赖消费引擎。
 * <p>
 * 可禁用功能插件（非必选）：{@code plugins.plugin-market.enabled=false} 时其托管业务 Bean（{@link PluginMarketController}
 * / {@link PluginMarketService}）缺席、贡献不进活动快照，市场页面 / API / 静态资源 / 导航入口因「未声明即 404」整体不可达；
 * 重新启用后恢复。全部市场 API 均 {@code ADMIN}（受 monitor 保护，绝不入访客 / 公开 / 邀请白名单）。
 * <p>
 * 无私有表（仓库列表是配置、catalog 是远端清单，均不落库），{@link #schema()} 为空。
 */
public class PluginMarketPlugin implements PixivFeaturePlugin {

    /** 插件 id（小写短横线）。 */
    public static final String ID = "plugin-market";

    @Override
    public String id() {
        return ID;
    }

    // 展示名 / 简介为纯 i18n key；namespace 由 displayNamespace() 默认取本插件首个 namespace（plugin-market）。
    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射，白名单外回退默认）：插件市场。
    @Override
    public String iconKey() {
        return "store";
    }

    @Override
    public String colorToken() {
        return "purple";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 管理员专属（方法不限，按 monitor 语义保护）：市场页面 + 页面专属静态资源 + 后端市场 API。
        // 路由由本插件声明、随其活动快照进出 —— 禁用 plugin-market 即三条声明缺席，对应 URL「未声明即 404」。
        return List.of(
                WebRouteContribution.admin("/plugin-market.html"),
                WebRouteContribution.admin("/plugin-market/**"),
                WebRouteContribution.admin("/api/plugin-market/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(
                "classpath:/static/plugin-market/", "/plugin-market/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 市场页面专属文案 + nav.label + 插件展示名 / 简介。第三参为 /api/i18n/meta 的全局展示顺序：
        // 追加在既有 namespace（…/plugins=20）之后。
        return List.of(new I18nContribution("plugin-market", "i18n.web.plugin-market", 21));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 插件市场入口只贡献给插件页内分段控件；顶部应用导航栏统一使用核心的「插件」入口，默认落到插件管理页。
        // ADMIN，仅管理员身份在 /api/navigation 可见；禁用 plugin-market 后该页内入口也自然缺席。
        return List.of(new NavigationContribution(
                ID,
                NavigationPlacements.PLUGINS_SEGMENT,
                "plugin-market", "nav.label", "/plugin-market.html",
                "store", AccessPolicy.ADMIN, 86));
    }
}
