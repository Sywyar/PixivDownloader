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
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.schedule.db.ScheduleSchemaContribution;
import top.sywyar.pixivdownload.schedule.source.EnumScheduledSourceProvider;
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
        // 跨页 / 横切的核心路由访问声明 + /api/downloaded 本地放行特例。功能页面与各自 API
        //（gallery/novel/stats/duplicate）由对应功能插件声明，本清单只承载未被功能插件接管的
        // 核心 / 共享路由。
        //
        // 访问级别 → AuthFilter 在请求侧读取本 registry 快照后派生回各访问清单：
        //   ADMIN_OR_SOLO   → 仅 monitor 清单（管理员 / solo 会话）
        //   GUEST_READ      → monitor 清单 + 访客邀请白名单（既受保护、受邀访客又可只读）
        //   GUEST_READ_OPEN → 仅访客白名单 / 公开共享静态依赖（不入 monitor；multi 普通访客 GET 亦可达）
        //   PUBLIC          → 公开静态资源 / 页面（solo 与 multi 两种模式均公开）
        //   LOCAL_ONLY      → /api/downloaded/** 本地放行特例（本地直通，远端回退常规鉴权）
        // 同一端点的不对称按现状保留、不改级别（如 /api/download/status/active 是 GUEST_READ、
        // 而 /api/download/status/ 前缀是 GUEST_READ_OPEN；/api/downloaded/batch 仅 ADMIN_OR_SOLO）。
        return List.of(
                // MONITOR_EXACT_PATHS 中仅 monitor（不在访客白名单）的精确条目
                adminOrSolo("/monitor.html"),
                adminOrSolo("/pixiv-invite-manage.html"),
                adminOrSolo("/pixiv-invite-detail.html"),
                adminOrSolo("/api/downloaded/batch"),
                // MONITOR_PREFIX_PATHS 中仅 monitor 的前缀条目
                adminOrSolo("/api/schedule/**"),
                adminOrSolo("/api/admin/**"),
                adminOrSolo("/api/tts/**"),
                adminOrSolo("/api/narration/**"),
                adminOrSolo("/monitor/**"),
                adminOrSolo("/pixiv-invite-manage/**"),
                adminOrSolo("/pixiv-invite-detail/**"),
                // MONITOR_EXACT_PATHS ∩ GUEST_ALLOWED_EXACT（monitor + 访客只读）
                guestRead("/api/downloaded/statistics"),
                guestRead("/api/downloaded/history"),
                guestRead("/api/downloaded/history/paged"),
                guestRead("/api/downloaded/by-move-folder"),
                guestRead("/api/download/status/active"),
                // MONITOR_PREFIX_PATHS ∩ GUEST_ALLOWED_PREFIX（monitor + 访客只读）
                guestRead("/api/downloaded/thumbnail/**"),
                guestRead("/api/downloaded/thumbnail-file/**"),
                guestRead("/api/downloaded/rawfile/**"),
                guestRead("/api/downloaded/image/**"),
                // /api/authors、/api/series、/api/collections 现状用无尾斜杠 startsWith 匹配
                // （同时命中 /api/authors 与 /api/authors/{id}）；模式以 ** 直接续接，去 ** 后还原为现状前缀。
                guestRead("/api/authors**"),
                guestRead("/api/series**"),
                guestRead("/api/collections**"),
                // GUEST_ALLOWED 但不入 monitor：只读代理 / 下载状态轮询前缀（multi 普通访客 GET 亦可达）
                guestReadOpen("/api/download/status/**"),
                guestReadOpen("/api/pixiv/artwork/**"),
                guestReadOpen("/api/pixiv/novel/**"),
                guestReadOpen("/api/tts/edge/voices"),
                guestReadOpenPost("/api/tts/edge/synthesize"),
                // GUEST_ALLOWED_STATIC_EXACT：跨页共享静态依赖（访客可读、不入 monitor，故同为 GUEST_READ_OPEN）。
                // 其中 i18n / 语言切换 / 主题三件同时也是 PUBLIC_STATIC_EXACT（见下），按现状两个清单都登记。
                guestReadOpen("/css/admin-visibility.css"),
                guestReadOpen("/css/lang-theme-switcher.css"),
                guestReadOpen("/css/pixiv-side-modules.css"),
                guestReadOpen("/css/pixiv-translate.css"),
                guestReadOpen("/js/invite-modals.js"),
                guestReadOpen("/js/pixiv-i18n.js"),
                guestReadOpen("/js/pixiv-lang-switcher.js"),
                guestReadOpen("/js/pixiv-novel-render.js"),
                guestReadOpen("/js/pixiv-side-modules.js"),
                guestReadOpen("/js/pixiv-theme.js"),
                guestReadOpen("/js/pixiv-translate.js"),
                // PUBLIC_STATIC_EXACT_PATHS（两种模式均公开）
                publicRoute("/favicon.ico"),
                publicRoute("/js/pixiv-i18n.js"),
                publicRoute("/js/pixiv-lang-switcher.js"),
                publicRoute("/js/pixiv-theme.js"),
                publicRoute("/maintenance.html"),
                // PUBLIC_PAGE_STATIC_PREFIX_PATHS（两种模式均公开）
                publicRoute("/index/**"),
                publicRoute("/intro/**"),
                publicRoute("/intro-canary/**"),
                publicRoute("/login/**"),
                publicRoute("/maintenance/**"),
                publicRoute("/vendor/fonts/**"),
                // /api/downloaded/{id} 本地放行特例（含 /api/download/status 精确）
                localOnly("/api/downloaded/**"),
                localOnly("/api/download/status"));
    }

    private static WebRouteContribution adminOrSolo(String pattern) {
        return new WebRouteContribution(pattern, AccessLevel.ADMIN_OR_SOLO, Set.of(), false);
    }

    private static WebRouteContribution guestRead(String pattern) {
        return new WebRouteContribution(pattern, AccessLevel.GUEST_READ, Set.of(), false);
    }

    private static WebRouteContribution guestReadOpen(String pattern) {
        return new WebRouteContribution(pattern, AccessLevel.GUEST_READ_OPEN, Set.of(), false);
    }

    private static WebRouteContribution guestReadOpenPost(String pattern) {
        return new WebRouteContribution(pattern, AccessLevel.GUEST_READ_OPEN, Set.of(HttpMethod.POST), false);
    }

    private static WebRouteContribution publicRoute(String pattern) {
        return new WebRouteContribution(pattern, AccessLevel.PUBLIC, Set.of(), false);
    }

    private static WebRouteContribution localOnly(String pattern) {
        return new WebRouteContribution(pattern, AccessLevel.LOCAL_ONLY, Set.of(), false);
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

    @Override
    public List<ScheduledSourceProvider> scheduledSources() {
        // 现有 7 个计划任务来源（USER_NEW / USER_REQUEST / SEARCH / SERIES / MY_BOOKMARKS /
        // FOLLOW_LATEST / COLLECTION）按既有枚举跨插画 / 小说统一调度，故由核心声明。
        // 各 provider 仅承载身份 + legacy 类型映射，发现 / 派发语义由调度器的枚举分支承载。
        return EnumScheduledSourceProvider.builtIn();
    }
}
