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
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.core.schedule.db.ScheduleSchemaContribution;
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
                WebRouteContribution.admin("/monitor.html"),
                WebRouteContribution.admin("/pixiv-invite-manage.html"),
                WebRouteContribution.admin("/pixiv-invite-detail.html"),
                WebRouteContribution.admin("/plugin-manage.html"),
                WebRouteContribution.admin("/api/downloaded/batch"),
                // （/api/schedule/** 随 schedule 能力收编进下载工作台，由 DownloadWorkbenchPlugin 声明）
                WebRouteContribution.admin("/api/admin/**"),
                // 插件管理后端 API（PluginManagementController）：状态查询 + 外置插件运行期生命周期动词 + 本地包安装，仅管理员。
                // 与恢复模式访问放行 /api/plugins/ 同前缀，使核心进入恢复模式时管理员仍可查询状态并驱动修复。
                WebRouteContribution.admin("/api/plugins/**"),
                WebRouteContribution.admin("/api/tts/**"),
                WebRouteContribution.admin("/api/narration/**"),
                WebRouteContribution.admin("/monitor/**"),
                WebRouteContribution.admin("/pixiv-invite-manage/**"),
                WebRouteContribution.admin("/pixiv-invite-detail/**"),
                // 插件管理页（plugin-manage.html）的页面专属静态资源（JS / CSS），仅管理员。
                WebRouteContribution.admin("/plugin-manage/**"),
                // ── monitor + 访客只读（受邀访客可读、multi 匿名访客被挡）─────────────────────────
                WebRouteContribution.invitedGuest("/api/downloaded/statistics"),
                WebRouteContribution.invitedGuest("/api/downloaded/history"),
                WebRouteContribution.invitedGuest("/api/downloaded/history/paged"),
                WebRouteContribution.invitedGuest("/api/downloaded/by-move-folder"),
                WebRouteContribution.invitedGuest("/api/download/status/active"),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail-file/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/rawfile/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/image/**"),
                // /api/authors、/api/series、/api/collections 用无尾斜杠 startsWith 匹配
                // （同时命中 /api/authors 与 /api/authors/{id}）；模式以 ** 直接续接，去 ** 后还原为现状前缀。
                WebRouteContribution.invitedGuest("/api/authors**"),
                WebRouteContribution.invitedGuest("/api/series**"),
                WebRouteContribution.invitedGuest("/api/collections**"),
                // 小说听书 TTS（edge）端点：与小说详情页同属受邀访客可读面（页面是 INVITED_GUEST、匿名访客读不到
                // 小说也就不应触达其 TTS），故声明为 INVITED_GUEST——既受 monitor 保护（匿名 multi 访客被挡、
                // 与历史「宽 /api/tts/** 前缀把它收进 monitor」逐字等价），又对受邀访客只读放行（synthesize 显式
                // POST 进访客 POST 白名单、voices 为 GET）。窄 INVITED_GUEST 声明经 RouteAccessRegistry.resolve
                // 覆盖宽 /api/tts/** = ADMIN：宽前缀不再吞掉这两个窄端点，AccessPolicy 即其真实可达面。
                WebRouteContribution.invitedGuest("/api/tts/edge/voices"),
                new WebRouteContribution("/api/tts/edge/synthesize", AccessPolicy.INVITED_GUEST, Set.of(HttpMethod.POST), false),
                // ── 访客可达、不入 monitor：只读代理 / 下载状态轮询前缀（multi 普通访客 GET 亦可达）──────
                WebRouteContribution.visitorAndInvitedGuest("/api/download/status/**"),
                WebRouteContribution.visitorAndInvitedGuest("/api/pixiv/artwork/**"),
                WebRouteContribution.visitorAndInvitedGuest("/api/pixiv/novel/**"),
                // 跨页共享只读静态依赖（访客可读、不入 monitor）。其中 i18n / 语言切换 / 主题三件
                // 同时也是 PUBLIC（见下），按现状两个清单都登记。
                WebRouteContribution.visitorAndInvitedGuest("/css/admin-visibility.css"),
                WebRouteContribution.visitorAndInvitedGuest("/css/lang-theme-switcher.css"),
                WebRouteContribution.visitorAndInvitedGuest("/css/pixiv-side-modules.css"),
                WebRouteContribution.visitorAndInvitedGuest("/css/pixiv-translate.css"),
                WebRouteContribution.visitorAndInvitedGuest("/js/invite-modals.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-i18n.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-lang-switcher.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-navigation.js"),
                // 通用页面区块渲染器：与 /api/page-sections（VISITOR_AND_INVITED_GUEST）同口径显式声明，
                // 使受邀访客页面也能加载该共享 section 渲染器；不依赖 /js/** 的 VISITOR 兜底（否则邀请访客 403）。
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-page-sections.js"),
                // 通用下钻渲染器：与 /api/drilldowns（VISITOR_AND_INVITED_GUEST）同口径显式声明，使受邀访客页面也能
                // 加载该共享下钻 helper；同 page-sections 不依赖 /js/** 的 VISITOR 兜底（否则邀请访客 403）。
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-drilldowns.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-novel-render.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-side-modules.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-theme.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-translate.js"),
                // 核心导航装配端点（NavigationController 读 NavigationRegistry 跨插件聚合、按身份可见性过滤）：
                // 改为 VISITOR_AND_INVITED_GUEST，使受邀访客也能为其画廊 / 小说页拉取动态导航（历史 VISITOR 会被
                // AuthFilter 挡成 403）；仍不入 monitor，multi 匿名访客与受邀访客各自只读得到对应身份可见导航。
                WebRouteContribution.visitorAndInvitedGuest("/api/navigation"),
                // 核心页面区块装配端点（PageSectionController 读 PageSectionRegistry 跨插件聚合、按身份可见性过滤）：
                // 同 /api/navigation 口径 VISITOR_AND_INVITED_GUEST，供宿主页面把活动插件贡献的区块渲染进 section slot。
                WebRouteContribution.visitorAndInvitedGuest("/api/page-sections"),
                // 核心下钻装配端点（DrilldownController 读 DrilldownRegistry 跨插件聚合、按身份可见性过滤）：
                // 同 /api/navigation 口径 VISITOR_AND_INVITED_GUEST，供宿主页面按语义 placement 解析活动插件贡献的下钻链接。
                WebRouteContribution.visitorAndInvitedGuest("/api/drilldowns"),
                // ── 公开（两种模式均公开）：基础页面、公开 API、公开静态前缀 ──────────────────────
                WebRouteContribution.publicRoute("/"),
                WebRouteContribution.publicRoute("/index"),
                WebRouteContribution.publicRoute("/index.html"),
                WebRouteContribution.publicRoute("/login.html"),
                WebRouteContribution.publicRoute("/intro.html"),
                WebRouteContribution.publicRoute("/intro-canary.html"),
                WebRouteContribution.publicRoute("/favicon.ico"),
                WebRouteContribution.publicRoute("/js/pixiv-i18n.js"),
                WebRouteContribution.publicRoute("/js/pixiv-lang-switcher.js"),
                WebRouteContribution.publicRoute("/js/pixiv-theme.js"),
                WebRouteContribution.publicRoute("/maintenance.html"),
                WebRouteContribution.publicRoute("/index/**"),
                WebRouteContribution.publicRoute("/intro/**"),
                WebRouteContribution.publicRoute("/intro-canary/**"),
                WebRouteContribution.publicRoute("/login/**"),
                WebRouteContribution.publicRoute("/maintenance/**"),
                WebRouteContribution.publicRoute("/vendor/fonts/**"),
                // 始终公开的核心横切 API（与 AuthFilter.isAlwaysPublicApi 内联口径一致）。
                WebRouteContribution.publicRoute("/api/auth/**"),
                WebRouteContribution.publicRoute("/api/i18n/**"),
                WebRouteContribution.publicRoute("/api/onboarding/**"),
                // ── VISITOR：multi 访客可达 / solo 需会话 / 邀请访客 403 / 不入 monitor ────────────
                // 共享只读静态目录的兜底（具体公开 / 访客文件以上面更宽松的策略优先命中）。
                WebRouteContribution.visitor("/js/**"),
                WebRouteContribution.visitor("/css/**"),
                WebRouteContribution.visitor("/vendor/**"),
                // setup / scripts：multi 公开、solo 需会话的不对称由 AuthFilter 内联 isDefaultPublicPath 承载；
                // 此处 VISITOR 声明不派生进任何清单、不改其内联访问行为，只为纳入「全 URL 声明」守卫。
                // /api/scripts** 用无尾斜杠 startsWith（同 /api/authors**）：覆盖裸列表端点 /api/scripts 与 /api/scripts/{id}。
                WebRouteContribution.visitor("/api/setup/**"),
                WebRouteContribution.visitor("/api/scripts**"),
                // （/api/navigation 改归上面的 VISITOR_AND_INVITED_GUEST 块，使受邀访客可读动态导航。）
                // 应用信息 / 配额 / 归档 / 迁移：随页面消费的访客可用 API（历史未声明 API 的涌现行为）。
                WebRouteContribution.visitor("/api/app/info"),
                WebRouteContribution.visitor("/api/quota/**"),
                WebRouteContribution.visitor("/api/archive/**"),
                WebRouteContribution.visitor("/api/migration/**"),
                // 下载进度 SSE 流（跨页消费、与下载状态轮询同归核心）。
                WebRouteContribution.visitor("/api/sse/**"),
                // Pixiv 只读代理的其余子面（artwork / novel 已在上面对访客开放；以下保持 VISITOR 现状）。
                WebRouteContribution.visitor("/api/pixiv/user/**"),
                WebRouteContribution.visitor("/api/pixiv/search**"),
                WebRouteContribution.visitor("/api/pixiv/novel-search**"),
                WebRouteContribution.visitor("/api/pixiv/series/**"),
                WebRouteContribution.visitor("/api/pixiv/me/**"),
                WebRouteContribution.visitor("/api/pixiv/thumbnail-proxy"),
                // ── GUI：本地 + token 双重校验（由 AuthFilter 内联分支执行；声明为纳入守卫与镜像）──────
                WebRouteContribution.gui("/api/gui/**"),
                // ── actuator 公开探针：容器健康 / 信息端点（由 AuthFilter 内联 fast-path 放行；声明为纳入
                //    路由镜像守卫与全 URL 声明模型，不改其内联放行行为）。仅以下四条对外暴露。──────────────
                WebRouteContribution.actuatorPublic("/actuator/health"),
                WebRouteContribution.actuatorPublic("/actuator/health/liveness"),
                WebRouteContribution.actuatorPublic("/actuator/health/readiness"),
                WebRouteContribution.actuatorPublic("/actuator/info"),
                // ── 本地放行：PAC、setup 页面 / 静态、已下载本地资产 ────────────────────────────────
                WebRouteContribution.local("/proxy.pac"),
                WebRouteContribution.local("/setup.html"),
                WebRouteContribution.local("/setup/**"),
                WebRouteContribution.local("/api/downloaded/**"),
                WebRouteContribution.local("/api/download/status"));
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
                new I18nContribution("maintenance", "i18n.web.maintenance", 19),
                // 插件管理页（plugin-manage.html）专属文案；同时承载无内容 namespace 的核心 / 计划任务宿主插件名称 / 简介。
                new I18nContribution("plugins", "i18n.web.plugins", 20));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 核心拥有的跨页基础入口：监控（管理员运行监控）、邀请码管理（管理员邀请治理）与插件管理（管理员插件治理）。
        // 三者均 ADMIN，仅管理员身份在 /api/navigation 可见（受邀访客 / multi 匿名访客看不到、点开本会 403）。标签走核心
        // 自有 i18n namespace（monitor / invite / plugins），与各功能插件的 nav.label 同一套「插件自有 i18n」机制。
        //
        // placement：监控是基础页面，进顶部栏 + 各侧栏（含中立主侧栏 app.sidebar，priority 20，仅次于下载工作台 10，
        // 排在功能页面之前）。邀请码管理是管理入口，只进各侧栏、不进顶部栏（priority 80：侧栏内最末，符合「管理入口
        // 在底部」现状）——这正是「invite-manage 不靠 data-nav-exclude 从顶部栏排除，而是只注册到适合的 placement」的体现。
        // 两者均把主入口同时贡献到 app.sidebar（统计等中立宿主页的主侧栏），与画廊 / 小说家族侧栏正交。
        return List.of(
                new NavigationContribution(
                        "monitor",
                        Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
                                NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR),
                        "monitor", "nav.label", "/monitor.html", "monitor", AccessPolicy.ADMIN, 20),
                new NavigationContribution(
                        "invite-manage",
                        Set.of(NavigationPlacements.APP_SIDEBAR, NavigationPlacements.GALLERY_SIDEBAR,
                                NavigationPlacements.NOVEL_SIDEBAR),
                        "invite", "nav.label", "/pixiv-invite-manage.html",
                        "invite-manage", AccessPolicy.ADMIN, 80),
                // 插件管理：顶部应用导航栏入口，与下载工作台 / 监控 / 画廊 / 小说同级（不进各侧栏——它是顶部栏级页面、
                // 非画廊 / 小说家族侧栏入口）。priority 85 仍为内置最大值，使其排在全部内置基础 / 功能页面之后
                //（顶部栏内「管理入口在末尾」，内置必选业务页面靠前）。
                new NavigationContribution(
                        "plugin-manage",
                        NavigationPlacements.APP_TOP,
                        "plugins", "nav.label", "/plugin-manage.html",
                        "puzzle", AccessPolicy.ADMIN, 85));
    }
}
