package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.author.AuthorSchemaContribution;
import top.sywyar.pixivdownload.collection.CollectionSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.ArtworkSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.FileNameSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.ImageHashSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.StatisticsSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.TagSchemaContribution;
import top.sywyar.pixivdownload.novel.db.NovelSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.schedule.db.ScheduleSchemaContribution;
import top.sywyar.pixivdownload.series.MangaSeriesSchemaContribution;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSchemaContribution;

import java.util.List;
import java.util.Set;

/**
 * 核心插件：承载核心层（schema、公共静态资源、基础路由等）的 contribution 声明。
 * <p>
 * 受管 schema 按领域拆成独立 contribution 类、但全部由核心声明——按「卸载投影测试」
 * （主人插件未安装时其他部件仍需要的表归核心），现存全部长期事实表的 ownerPluginId
 * 一律为 core；功能插件的 {@code schema()} 目前为空，插件私有表只在未来出现
 * 交互状态 / 临时队列 / 可重建缓存时才产生。
 */
public class CorePlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "core";
    }

    @Override
    public String displayName() {
        return "核心";
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
        // 跨页 / 横切的核心路由访问声明：功能页面与各自 API（gallery/novel/stats/duplicate）由对应功能插件
        // 声明，本清单承载未被功能插件接管的核心 / 共享路由，并把历史「未声明 API / 顶层 HTML / 静态资源」
        // 一并显式声明——AuthFilter 对未命中任何声明的请求统一 404，故每个真实 URL 都必须落在某条声明里。
        //
        // 访问策略 → AuthFilter 请求侧消费 registry 快照：monitor 受保护与「未声明即 404」按
        // RouteAccessRegistry.resolve(path, method) 解析「最具体声明 + 方法」的有效策略（窄声明覆盖宽前缀，
        // 宽前缀不吞窄端点）；访客白名单 / 公开 / 本地清单仍按访问策略派生。
        //   ADMIN                     → monitor 受保护（管理员 / solo 会话）
        //   INVITED_GUEST             → monitor 受保护 + 访客邀请白名单（既受保护、受邀访客又可只读）
        //   VISITOR_AND_INVITED_GUEST → 仅访客白名单 / 共享只读依赖（不入 monitor；multi 普通访客 GET 亦可达）
        //   VISITOR                   → 不派生进任何清单（multi 访客可读 / solo 需会话 / 邀请访客 403 /
        //                               不入 monitor，与历史未声明 API 逐字等价；纯归属声明、不改访问行为）
        //   PUBLIC                    → 公开静态资源 / 页面 / API（solo 与 multi 两种模式均公开）
        //   LOCAL                     → 本地放行特例（本地直通，远端回退常规鉴权）
        //   GUI                       → /api/gui/** 双重校验（本地 + token，由内联分支执行；声明为纳入守卫）
        //   ACTUATOR_PUBLIC           → actuator 公开探针（由内联 fast-path 放行；声明为纳入守卫与镜像）
        // 同一端点的不对称按现状保留（如 /api/download/status/active 是 INVITED_GUEST、而 /api/download/status/
        // 前缀是 VISITOR_AND_INVITED_GUEST；/api/downloaded/batch 仅 ADMIN）。声明顺序仅为可读性，AuthFilter 按
        // 访问策略 / resolve 特异性判定而非声明顺序。VISITOR / GUI / ACTUATOR_PUBLIC 多为「声明但由内联分支判定」。
        return List.of(
                // ── monitor：仅管理员（不在访客白名单）的页面与 API ───────────────────────────────
                admin("/monitor.html"),
                admin("/pixiv-invite-manage.html"),
                admin("/pixiv-invite-detail.html"),
                admin("/api/downloaded/batch"),
                // （/api/schedule/** 随 schedule 能力收编进下载工作台，由 DownloadWorkbenchPlugin 声明）
                admin("/api/admin/**"),
                admin("/api/tts/**"),
                admin("/api/narration/**"),
                admin("/monitor/**"),
                admin("/pixiv-invite-manage/**"),
                admin("/pixiv-invite-detail/**"),
                // ── monitor + 访客只读（受邀访客可读、multi 匿名访客被挡）─────────────────────────
                invitedGuest("/api/downloaded/statistics"),
                invitedGuest("/api/downloaded/history"),
                invitedGuest("/api/downloaded/history/paged"),
                invitedGuest("/api/downloaded/by-move-folder"),
                invitedGuest("/api/download/status/active"),
                invitedGuest("/api/downloaded/thumbnail/**"),
                invitedGuest("/api/downloaded/thumbnail-file/**"),
                invitedGuest("/api/downloaded/rawfile/**"),
                invitedGuest("/api/downloaded/image/**"),
                // /api/authors、/api/series、/api/collections 用无尾斜杠 startsWith 匹配
                // （同时命中 /api/authors 与 /api/authors/{id}）；模式以 ** 直接续接，去 ** 后还原为现状前缀。
                invitedGuest("/api/authors**"),
                invitedGuest("/api/series**"),
                invitedGuest("/api/collections**"),
                // 小说听书 TTS（edge）端点：与小说详情页同属受邀访客可读面（页面是 INVITED_GUEST、匿名访客读不到
                // 小说也就不应触达其 TTS），故声明为 INVITED_GUEST——既受 monitor 保护（匿名 multi 访客被挡、
                // 与历史「宽 /api/tts/** 前缀把它收进 monitor」逐字等价），又对受邀访客只读放行（synthesize 显式
                // POST 进访客 POST 白名单、voices 为 GET）。窄 INVITED_GUEST 声明经 RouteAccessRegistry.resolve
                // 覆盖宽 /api/tts/** = ADMIN：宽前缀不再吞掉这两个窄端点，AccessPolicy 即其真实可达面。
                invitedGuest("/api/tts/edge/voices"),
                invitedGuestPost("/api/tts/edge/synthesize"),
                // ── 访客可达、不入 monitor：只读代理 / 下载状态轮询前缀（multi 普通访客 GET 亦可达）──────
                visitorAndInvitedGuest("/api/download/status/**"),
                visitorAndInvitedGuest("/api/pixiv/artwork/**"),
                visitorAndInvitedGuest("/api/pixiv/novel/**"),
                // 跨页共享只读静态依赖（访客可读、不入 monitor）。其中 i18n / 语言切换 / 主题三件
                // 同时也是 PUBLIC（见下），按现状两个清单都登记。
                visitorAndInvitedGuest("/css/admin-visibility.css"),
                visitorAndInvitedGuest("/css/lang-theme-switcher.css"),
                visitorAndInvitedGuest("/css/pixiv-side-modules.css"),
                visitorAndInvitedGuest("/css/pixiv-translate.css"),
                visitorAndInvitedGuest("/js/invite-modals.js"),
                visitorAndInvitedGuest("/js/pixiv-i18n.js"),
                visitorAndInvitedGuest("/js/pixiv-lang-switcher.js"),
                visitorAndInvitedGuest("/js/pixiv-novel-render.js"),
                visitorAndInvitedGuest("/js/pixiv-side-modules.js"),
                visitorAndInvitedGuest("/js/pixiv-theme.js"),
                visitorAndInvitedGuest("/js/pixiv-translate.js"),
                // ── 公开（两种模式均公开）：基础页面、公开 API、公开静态前缀 ──────────────────────
                publicRoute("/"),
                publicRoute("/index"),
                publicRoute("/index.html"),
                publicRoute("/login.html"),
                publicRoute("/intro.html"),
                publicRoute("/intro-canary.html"),
                publicRoute("/favicon.ico"),
                publicRoute("/js/pixiv-i18n.js"),
                publicRoute("/js/pixiv-lang-switcher.js"),
                publicRoute("/js/pixiv-theme.js"),
                publicRoute("/maintenance.html"),
                publicRoute("/index/**"),
                publicRoute("/intro/**"),
                publicRoute("/intro-canary/**"),
                publicRoute("/login/**"),
                publicRoute("/maintenance/**"),
                publicRoute("/vendor/fonts/**"),
                // 始终公开的核心横切 API（与 AuthFilter.isAlwaysPublicApi 内联口径一致）。
                publicRoute("/api/auth/**"),
                publicRoute("/api/i18n/**"),
                publicRoute("/api/onboarding/**"),
                // ── VISITOR：multi 访客可达 / solo 需会话 / 邀请访客 403 / 不入 monitor ────────────
                // 共享只读静态目录的兜底（具体公开 / 访客文件以上面更宽松的策略优先命中）。
                visitor("/js/**"),
                visitor("/css/**"),
                visitor("/vendor/**"),
                // setup / scripts：multi 公开、solo 需会话的不对称由 AuthFilter 内联 isDefaultPublicPath 承载；
                // 此处 VISITOR 声明不派生进任何清单、不改其内联访问行为，只为纳入「全 URL 声明」守卫。
                // /api/scripts** 用无尾斜杠 startsWith（同 /api/authors**）：覆盖裸列表端点 /api/scripts 与 /api/scripts/{id}。
                visitor("/api/setup/**"),
                visitor("/api/scripts**"),
                // 核心导航装配端点（NavigationController 读 NavigationRegistry 跨插件聚合、按可见性过滤）。
                visitor("/api/navigation"),
                // 应用信息 / 配额 / 归档 / 迁移：随页面消费的访客可用 API（历史未声明 API 的涌现行为）。
                visitor("/api/app/info"),
                visitor("/api/quota/**"),
                visitor("/api/archive/**"),
                visitor("/api/migration/**"),
                // 下载进度 SSE 流（跨页消费、与下载状态轮询同归核心）。
                visitor("/api/sse/**"),
                // Pixiv 只读代理的其余子面（artwork / novel 已在上面对访客开放；以下保持 VISITOR 现状）。
                visitor("/api/pixiv/user/**"),
                visitor("/api/pixiv/search**"),
                visitor("/api/pixiv/novel-search**"),
                visitor("/api/pixiv/series/**"),
                visitor("/api/pixiv/me/**"),
                visitor("/api/pixiv/thumbnail-proxy"),
                // ── GUI：本地 + token 双重校验（由 AuthFilter 内联分支执行；声明为纳入守卫与镜像）──────
                gui("/api/gui/**"),
                // ── actuator 公开探针：容器健康 / 信息端点（由 AuthFilter 内联 fast-path 放行；声明为纳入
                //    路由镜像守卫与全 URL 声明模型，不改其内联放行行为）。仅以下四条对外暴露。──────────────
                actuatorPublic("/actuator/health"),
                actuatorPublic("/actuator/health/liveness"),
                actuatorPublic("/actuator/health/readiness"),
                actuatorPublic("/actuator/info"),
                // ── 本地放行：PAC、setup 页面 / 静态、已下载本地资产 ────────────────────────────────
                local("/proxy.pac"),
                local("/setup.html"),
                local("/setup/**"),
                local("/api/downloaded/**"),
                local("/api/download/status"));
    }

    private static WebRouteContribution admin(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.ADMIN, Set.of(), false);
    }

    private static WebRouteContribution invitedGuest(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.INVITED_GUEST, Set.of(), false);
    }

    private static WebRouteContribution visitorAndInvitedGuest(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.VISITOR_AND_INVITED_GUEST, Set.of(), false);
    }

    private static WebRouteContribution invitedGuestPost(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.INVITED_GUEST, Set.of(HttpMethod.POST), false);
    }

    /**
     * 「会话用户或 multi 访客」端点（{@link AccessPolicy#VISITOR}）：multi 普通访客可访问、solo 需会话、
     * 邀请访客 403、不入 monitor。该策略不被 {@code AuthFilter} 派生进任何访问清单，命中后落默认会话 /
     * 访客分支（或仍由内联公开分支判定），访问行为与未声明该路由时逐字等价——声明只为纳入路由归属、镜像与
     * 全 URL 声明守卫。
     */
    private static WebRouteContribution visitor(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.VISITOR, Set.of(), false);
    }

    private static WebRouteContribution publicRoute(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.PUBLIC, Set.of(), false);
    }

    private static WebRouteContribution local(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.LOCAL, Set.of(), false);
    }

    /**
     * {@code /api/gui/**} 双重校验端点（{@link AccessPolicy#GUI}）：实际放行由 {@code AuthFilter} 的内联
     * GUI 分支（本地可信请求 + GUI token）执行；声明为纳入路由镜像与全 URL 声明守卫，不改其内联访问行为。
     */
    private static WebRouteContribution gui(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.GUI, Set.of(), false);
    }

    /**
     * actuator 公开探针端点（{@link AccessPolicy#ACTUATOR_PUBLIC}）：实际放行由 {@code AuthFilter} 的内联
     * actuator fast-path（{@code isPublicActuatorEndpoint}，先于鉴权 / 维护窗口 / 限流）执行；声明为纳入
     * 路由镜像守卫与全 URL 声明模型，不改其内联放行行为。仅 health（含 liveness/readiness）与 info 对外暴露，
     * 该策略仅 core 可声明、仅用于这些探针路径（见 {@code RouteAccessMirrorTest}）。
     */
    private static WebRouteContribution actuatorPublic(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.ACTUATOR_PUBLIC, Set.of(), false);
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        // 共享公共库（侧边模块 / i18n / 主题 / 语言切换 / 翻译等脚本、共享样式、第三方 vendor）
        // 作为核心公共资源声明：被所有页面跨插件复用，解析经核心 ClassLoader。本记录只描述 serving；
        // 逐文件访问（公开 / 邀请访客放行）由 routes() / RouteAccessRegistry 声明、AuthFilter 执行。
        return List.of(
                new StaticResourceContribution("core", "classpath:/static/js/", "/js/"),
                new StaticResourceContribution("core", "classpath:/static/css/", "/css/"),
                new StaticResourceContribution("core", "classpath:/static/vendor/", "/vendor/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 管理 / 安全 / 引导页与跨插件共享文案留核心：common（全站公共）、setup/login/intro
        // （首次安装与登录引导）、monitor（运行监控）、invite（访客邀请）、tour（新手引导）、
        // maintenance（维护窗口）；translate（AI 翻译文案）被小说详情页与系列页跨插件消费、
        // 同 tour 模式留核心，终局归宿是后续的 AI 翻译插件、届时随功能整体迁出。
        // 第三参为 /api/i18n/meta 的展示顺序：核心与功能插件交错排列，故各 namespace 自带
        // 全局序号，使合并结果不随插件注册先后漂移（保持历史 namespace 顺序）。
        return List.of(
                new I18nContribution("common", "i18n.web.common", 1),
                new I18nContribution("setup", "i18n.web.setup", 2),
                new I18nContribution("login", "i18n.web.login", 3),
                new I18nContribution("intro", "i18n.web.intro", 4),
                new I18nContribution("translate", "i18n.web.translate", 13),
                new I18nContribution("monitor", "i18n.web.monitor", 15),
                new I18nContribution("invite", "i18n.web.invite", 17),
                new I18nContribution("tour", "i18n.web.tour", 18),
                new I18nContribution("maintenance", "i18n.web.maintenance", 19));
    }
}
