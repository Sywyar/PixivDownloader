package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.author.AuthorSchemaContribution;
import top.sywyar.pixivdownload.collection.CollectionSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.ArtworkSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.FileNameSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.ImageHashSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.NovelSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.StatisticsSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.TagSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.core.schedule.db.ScheduleSchemaContribution;
import top.sywyar.pixivdownload.series.MangaSeriesSchemaContribution;
import top.sywyar.pixivdownload.setup.guest.persistence.GuestInviteSchemaContribution;

import java.util.List;
import java.util.Set;

/**
 * 核心插件：承载核心层（schema、公共静态资源、基础路由等）的 contribution 声明。
 * <p>
 * 受管 schema 按领域拆成独立 contribution 类。宿主共享长期事实表由核心声明，
 * 插件自有长期事实表由对应功能插件通过 {@code schema()} 声明；运行期 registry
 * 以注册中心捕获的稳定插件 id 盖章所有权，并从安装态插件合并 schema，插件禁用
 * 不会导致其已声明的受管表缺席。
 */
public class CorePlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "core";
    }

    // 展示名 / 简介为纯 i18n key，namespace 由 displayNamespace() 提供。核心插件无单一内容 namespace、GUI 不呈现它
    // （必选），但 Web 插件管理页会展示并解析；故 displayNamespace() 显式借用插件管理页的 plugins namespace。
    @Override
    public String displayNamespace() {
        return "plugins";
    }

    @Override
    public String displayName() {
        return "name.core";
    }

    @Override
    public String description() {
        return "summary.core";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射）：核心壳。
    @Override
    public String iconKey() {
        return "gear";
    }

    @Override
    public String colorToken() {
        return "blue";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.CORE;
    }

    @Override
    public List<SchemaContribution> schema() {
        return List.of(
                CoreSchemaContribution.CONTRIBUTION,
                ArtworkSchemaContribution.CONTRIBUTION,
                FileNameSchemaContribution.CONTRIBUTION,
                StatisticsSchemaContribution.CONTRIBUTION,
                TagSchemaContribution.CONTRIBUTION,
                ImageHashSchemaContribution.CONTRIBUTION,
                AuthorSchemaContribution.CONTRIBUTION,
                MangaSeriesSchemaContribution.CONTRIBUTION,
                CollectionSchemaContribution.CONTRIBUTION,
                GuestInviteSchemaContribution.CONTRIBUTION,
                NovelSchemaContribution.CONTRIBUTION,
                ScheduleSchemaContribution.CONTRIBUTION);
    }

    @Override
    public List<WebRouteContribution> routes() {
        // AuthFilter 复用本次请求按路径和方法解析的最具体声明；公开、邀请与本地授权不另走派生清单。
        // GUI、setup、PAC、actuator 等入口仍执行各自流程校验，声明本身不替代这些校验。
        return List.of(
                // ── monitor：仅管理员（不在访客白名单）的页面与 API ───────────────────────────────
                WebRouteContribution.admin("/monitor.html"),
                WebRouteContribution.admin("/pixiv-invite-manage.html"),
                WebRouteContribution.admin("/pixiv-invite-detail.html"),
                WebRouteContribution.admin("/plugin-manage.html"),
                WebRouteContribution.admin("/api/downloaded/batch"),
                WebRouteContribution.admin("/api/admin/**"),
                // 插件管理后端 API（PluginManagementController）：状态查询 + 外置插件运行期生命周期动词 + 本地包安装，仅管理员。
                // 与恢复模式访问放行 /api/plugins/ 同前缀，使核心进入恢复模式时管理员仍可查询状态并驱动修复。
                WebRouteContribution.admin("/api/plugins/**"),
                WebRouteContribution.admin("/monitor/**"),
                WebRouteContribution.admin("/pixiv-invite-manage/**"),
                WebRouteContribution.admin("/pixiv-invite-detail/**"),
                // 插件管理页（plugin-manage.html）的页面专属静态资源（JS / CSS），仅管理员。
                WebRouteContribution.admin("/plugin-manage/**"),
                // ── monitor + 访客只读（受邀访客可读、multi 匿名游客被挡）─────────────────────────
                WebRouteContribution.invitedGuest("/api/downloaded/statistics"),
                WebRouteContribution.invitedGuest("/api/downloaded/history"),
                WebRouteContribution.invitedGuest("/api/downloaded/history/paged"),
                WebRouteContribution.invitedGuest("/api/downloaded/by-move-folder"),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail-file/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/rawfile/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/image/**"),
                // /api/authors、/api/series、/api/collections 用无尾斜杠 startsWith 匹配
                // （同时命中 /api/authors 与 /api/authors/{id}）；模式以 ** 直接续接，去 ** 后还原为现状前缀。
                WebRouteContribution.invitedGuest("/api/authors**"),
                WebRouteContribution.invitedGuest("/api/series**"),
                WebRouteContribution.invitedGuest("/api/collections**"),
                new WebRouteContribution("/api/collections/memberships", AccessPolicy.INVITED_GUEST,
                        Set.of(HttpMethod.POST), false),
                new WebRouteContribution("/api/collections/novels/memberships", AccessPolicy.INVITED_GUEST,
                        Set.of(HttpMethod.POST), false),
                // ── 访客可达、不入 monitor：共享只读静态依赖（multi 游客 GET 亦可达）──────────────
                // 跨页共享只读静态依赖（访客可读、不入 monitor）。公开资源只在下方 PUBLIC 清单登记，
                // 避免同一路径出现互相冲突的访问策略。
                WebRouteContribution.visitorAndInvitedGuest("/css/admin-visibility.css"),
                WebRouteContribution.visitorAndInvitedGuest("/css/pixiv-side-modules.css"),
                WebRouteContribution.visitorAndInvitedGuest("/js/invite-modals.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-navigation.js"),
                // 通用页面区块渲染器：与 /api/page-sections（VISITOR_AND_INVITED_GUEST）同口径显式声明，
                // 使受邀访客页面也能加载该共享 section 渲染器；不依赖 /js/** 的 VISITOR 兜底（否则邀请访客 403）。
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-page-sections.js"),
                // 通用下钻渲染器：与 /api/drilldowns（VISITOR_AND_INVITED_GUEST）同口径显式声明，使受邀访客页面也能
                // 加载该共享下钻 helper；同 page-sections 不依赖 /js/** 的 VISITOR 兜底（否则邀请访客 403）。
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-drilldowns.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-side-modules.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-vue.js"),
                WebRouteContribution.visitorAndInvitedGuest("/vendor/vue/vue.global.prod.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding-core.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding-overlay.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding-download.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding-gallery.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding.js"),
                WebRouteContribution.visitorAndInvitedGuest("/css/pixiv-onboarding.css"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-ui-slots.js"),
                // 装配端点按当前身份裁剪贡献；共享资源须允许邀请访客读取，不能依赖 VISITOR 目录兜底。
                WebRouteContribution.visitorAndInvitedGuest("/api/navigation"),
                WebRouteContribution.visitorAndInvitedGuest("/api/page-sections"),
                WebRouteContribution.visitorAndInvitedGuest("/api/drilldowns"),
                // 通用 UI 槽位清单：宿主页面按 target 前缀加载活动插件声明的同源渲染模块。
                WebRouteContribution.visitorAndInvitedGuest("/api/web/ui-slots"),
                // ── 公开（两种模式均公开）：基础页面、公开 API、公开静态前缀 ──────────────────────
                WebRouteContribution.publicRoute("/"),
                WebRouteContribution.publicRoute("/index"),
                WebRouteContribution.publicRoute("/index.html"),
                WebRouteContribution.publicRoute("/login.html"),
                WebRouteContribution.publicRoute("/intro.html"),
                WebRouteContribution.publicRoute("/intro-canary.html"),
                WebRouteContribution.publicRoute("/favicon.ico"),
                WebRouteContribution.publicRoute("/css/pixiv-layout.css"),
                WebRouteContribution.publicRoute("/css/pixiv-motion.css"),
                WebRouteContribution.publicRoute("/css/lang-theme-switcher.css"),
                WebRouteContribution.publicRoute("/css/pixiv-feedback.css"),
                WebRouteContribution.publicRoute("/css/pixiv-scrollbar.css"),
                WebRouteContribution.publicRoute("/js/pixiv-feedback.js"),
                WebRouteContribution.publicRoute("/js/pixiv-actions.js"),
                WebRouteContribution.publicRoute("/js/pixiv-i18n.js"),
                WebRouteContribution.publicRoute("/js/pixiv-lang-switcher.js"),
                WebRouteContribution.publicRoute("/js/pixiv-survey-frame-bridge.js"),
                WebRouteContribution.publicRoute("/js/pixiv-theme.js"),
                WebRouteContribution.publicRoute("/js/pixiv-layout.js"),
                // 品牌化错误状态页（400/403/404/429/500/502/503 与 4xx/5xx 兜底）。错误派发本身
                // 由容器 sendError → Spring Boot 静态错误视图解析完成（ERROR dispatch 旁路 AuthFilter）；
                // 这里声明的是页面与其共享 CSS/JS 被浏览器以普通 REQUEST 直接加载时的公开访问——
                // 刚拿到错误的任何身份（含未登录 / 受邀访客）都必须能渲染这些页面。
                WebRouteContribution.publicRoute("/error/**"),
                WebRouteContribution.publicRoute("/maintenance.html"),
                WebRouteContribution.publicRoute("/index/**"),
                WebRouteContribution.publicRoute("/intro/**"),
                WebRouteContribution.publicRoute("/intro-canary/**"),
                WebRouteContribution.publicRoute("/login/**"),
                WebRouteContribution.publicRoute("/maintenance/**"),
                WebRouteContribution.publicRoute("/vendor/fonts/**"),
                WebRouteContribution.publicRoute("/api/auth/**"),
                WebRouteContribution.publicRoute("/api/i18n/**"),
                WebRouteContribution.publicRoute("/api/onboarding/**"),
                // ── VISITOR：multi 游客可达 / solo 需会话 / 邀请访客 403 / 不入 monitor ────────────
                // 目录兜底不覆盖更具体的文件声明。
                WebRouteContribution.visitor("/js/**"),
                WebRouteContribution.visitor("/css/**"),
                WebRouteContribution.visitor("/vendor/**"),
                // setup API 在尚未初始化或 multi 模式时走引导例外，已完成初始化的 solo 仍需会话。
                WebRouteContribution.visitor("/api/setup/**"),
                WebRouteContribution.visitor("/api/app/info"),
                WebRouteContribution.visitor("/api/quota/**"),
                WebRouteContribution.visitor("/api/archive/**"),
                WebRouteContribution.visitor("/api/migration/**"),
                // ── GUI：本地 + token 双重校验（由 AuthFilter 内联分支执行；声明为纳入守卫与镜像）──────
                WebRouteContribution.gui("/api/gui/**"),
                // ── actuator 公开探针：容器健康 / 信息端点（由 AuthFilter 内联 fast-path 放行；声明为纳入
                //    路由镜像守卫与全 URL 声明模型，不改其内联放行行为）。仅以下四条对外暴露。──────────────
                actuatorPublicProbe("/actuator/health"),
                actuatorPublicProbe("/actuator/health/liveness"),
                actuatorPublicProbe("/actuator/health/readiness"),
                actuatorPublicProbe("/actuator/info"),
                // ── 本地放行：PAC、setup 页面 / 静态、已下载本地资产 ────────────────────────────────
                WebRouteContribution.local("/proxy.pac"),
                WebRouteContribution.local("/setup.html"),
                WebRouteContribution.local("/setup/**"),
                WebRouteContribution.local("/__dev/error/**"),
                WebRouteContribution.local("/api/downloaded/**"));
    }

    /** 宿主私有的 actuator 探针声明入口；第三方 Plugin API 不暴露对应命名工厂。 */
    private static WebRouteContribution actuatorPublicProbe(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.ACTUATOR_PUBLIC, Set.of(), false);
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        // 共享公共库（侧边模块 / i18n / 主题 / 语言切换 / 翻译等脚本、共享样式、第三方 vendor）
        // 作为核心公共资源声明：被所有页面跨插件复用，解析经核心 ClassLoader。本记录只描述 serving；
        // 逐文件访问（公开 / 邀请访客放行）由 routes() / RouteAccessRegistry 声明、AuthFilter 执行。
        return List.of(
                new StaticResourceContribution("classpath:/static/js/", "/js/"),
                new StaticResourceContribution("classpath:/static/css/", "/css/"),
                new StaticResourceContribution("classpath:/static/vendor/", "/vendor/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 管理 / 安全 / 引导页与跨插件共享文案留核心：common（全站公共）、setup/login/intro
        // （首次安装与登录引导）、monitor（运行监控）、invite（访客邀请）、tour（新手引导）、
        // maintenance（维护窗口）。AI 翻译文案由 AI 插件贡献 translate namespace。
        // 第三参为 /api/i18n/meta 的展示顺序：核心与功能插件交错排列，故各 namespace 自带
        // 全局序号，使合并结果不随插件注册先后漂移（保持历史 namespace 顺序）。
        return List.of(
                new I18nContribution("common", "i18n.web.common", 1),
                new I18nContribution("setup", "i18n.web.setup", 2),
                new I18nContribution("login", "i18n.web.login", 3),
                new I18nContribution("intro", "i18n.web.intro", 4),
                new I18nContribution("monitor", "i18n.web.monitor", 15),
                new I18nContribution("invite", "i18n.web.invite", 17),
                new I18nContribution("tour", "i18n.web.tour", 18),
                new I18nContribution("maintenance", "i18n.web.maintenance", 19),
                // 错误状态页（/error/*.html）专属文案。
                new I18nContribution("status", "i18n.web.status", 22),
                // 插件管理页（plugin-manage.html）专属文案；同时承载无内容 namespace 的核心 / 计划任务宿主插件名称 / 简介。
                new I18nContribution("plugins", "i18n.web.plugins", 20));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 核心拥有的跨页基础入口：邀请码管理（管理员邀请治理）与插件管理（管理员插件治理）。
        // 二者均 ADMIN，仅管理员身份在 /api/navigation 可见。标签走核心
        // 自有 i18n namespace（invite / plugins），与各功能插件的 nav.label 同一套「插件自有 i18n」机制。
        //
        // 邀请管理属于核心安全能力；可选内容插件撤回后仍可管理和撤销已有邀请。
        // 仅贡献到 Web 侧栏与桌面安全入口，不占用顶部应用导航。
        return List.of(
                new NavigationContribution(
                        "invite-manage",
                        Set.of(NavigationPlacements.APP_SIDEBAR, NavigationPlacements.GALLERY_SIDEBAR_TOOLS,
                                NavigationPlacements.NOVEL_SIDEBAR_TOOLS, NavigationPlacements.DESKTOP_SECURITY_ACTIONS),
                        "invite", "nav.label", "/pixiv-invite-manage.html",
                        "invite-manage", AccessPolicy.ADMIN, 80, Set.of(), "nav.description"),
                // 插件管理与下载、画廊同属主页面入口。
                new NavigationContribution(
                        "plugin-manage",
                        Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.GALLERY_SIDEBAR,
                                NavigationPlacements.NOVEL_SIDEBAR),
                        "plugins", "nav.label", "/plugin-manage.html",
                        "puzzle", AccessPolicy.ADMIN, 85, Set.of("plugin-update-summary"), null));
    }
}
