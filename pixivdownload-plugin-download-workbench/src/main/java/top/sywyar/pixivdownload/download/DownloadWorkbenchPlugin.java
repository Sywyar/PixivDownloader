package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.DownloadGalleryCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadQueueCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadScheduleCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;

import java.util.List;
import java.util.Set;

/**
 * 下载工作台插件：{@code pixiv-batch} 页面、下载队列、油猴脚本入口与下载执行。
 * <p>
 * 计划任务安全壳随下载工作台外置包加载：调度 tick / 队列 / 限流 / 熔断 / cookie·proxy 作用域 /
 * 隔离重试 / 水位线以及 {@code /api/schedule/**} 路由均在本外置包上下文内装配。下载工作台通过
 * {@link #scheduledSourceDescriptors()} 声明 7 个内置来源，并由 child context 中的
 * {@code ScheduledSourceExecutor} / {@code PixivScheduledIllustWorkExecutor} 执行发现与下载。
 * <p>
 * 核心只保留下载历史 / 统计 / 本地资产 serving 等长期事实 API（{@code /api/downloaded/*}）；下载提交、
 * 队列状态、Pixiv 抓取代理、SSE 和 userscript 分发入口均随本插件启停。
 */
public class DownloadWorkbenchPlugin implements PixivFeaturePlugin {

    /** 下载工作台插件 id：下载进度 SSE 推流随该插件运行期归属（停用 / 卸载时其推流被统一关闭）。 */
    public static final String ID = "download-workbench";

    @Override
    public String id() {
        return ID;
    }

    // 展示名 / 简介为纯 i18n key；namespace 由 displayNamespace() 默认取本插件首个 namespace（batch）。下载工作台必选、
    // 不在配置页「插件」分组呈现（GUI 只列可禁用功能插件），但 Web 插件管理页会展示并解析它，故 key 须真实存在于 batch。
    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射）：下载工作台。
    @Override
    public String iconKey() {
        return "download";
    }

    @Override
    public String colorToken() {
        return "pixiv";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    /** 下载工作台是必选插件：下载页与下载执行是核心使用路径，不允许关闭。 */
    @Override
    public boolean required() {
        return true;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 下载页与其提交 / 队列 / 状态 API：下载页 /pixiv-batch.html、其拆分静态目录 /pixiv-batch/**，以及
        // 下载提交（/api/download/pixiv）、取消（/api/cancel/**、/api/download/cancel/**）、队列清理
        //（/api/download/queue/**）、批量状态（/api/batch/**）、扩展点装配（/api/download/extensions）一律
        // VISITOR——复刻现状「未受管页面 / 未声明 API」的涌现行为：multi 访客可达（走配额） / solo 需会话 /
        // 邀请访客 403 / 不入 monitor。AuthFilter 不为 VISITOR 派生任何清单、命中后落默认会话 / 访客分支，
        // 访问行为与未声明时逐字等价；声明只为消除「未声明路由」歧义、纳入路由归属与全 URL 声明守卫。
        return List.of(
                WebRouteContribution.visitor("/pixiv-batch.html"),
                WebRouteContribution.visitor("/pixiv-batch/**"),
                WebRouteContribution.admin("/api/schedule/**"),
                WebRouteContribution.invitedGuest("/api/download/status/active"),
                WebRouteContribution.visitorAndInvitedGuest("/api/download/status/**"),
                WebRouteContribution.local("/api/download/status"),
                WebRouteContribution.visitorAndInvitedGuest("/api/pixiv/artwork/**"),
                WebRouteContribution.visitor("/api/pixiv/user/*/artworks"),
                WebRouteContribution.visitor("/api/pixiv/user/*/request-artworks"),
                WebRouteContribution.visitor("/api/pixiv/user/*/meta"),
                WebRouteContribution.visitor("/api/pixiv/user/*/illust-cards"),
                WebRouteContribution.visitor("/api/pixiv/search**"),
                WebRouteContribution.visitor("/api/pixiv/series/**"),
                WebRouteContribution.visitor("/api/pixiv/me/uid"),
                WebRouteContribution.visitor("/api/pixiv/me/illust-bookmarks"),
                WebRouteContribution.visitor("/api/pixiv/me/following"),
                WebRouteContribution.visitor("/api/pixiv/me/follow-latest"),
                WebRouteContribution.visitor("/api/pixiv/me/collections"),
                WebRouteContribution.visitor("/api/pixiv/me/collection/*/works"),
                WebRouteContribution.visitor("/api/pixiv/thumbnail-proxy"),
                WebRouteContribution.visitor("/api/scripts**"),
                WebRouteContribution.visitor("/api/sse/**"),
                WebRouteContribution.visitor("/api/download/pixiv"),
                WebRouteContribution.visitor("/api/cancel/**"),
                WebRouteContribution.visitor("/api/download/cancel/**"),
                WebRouteContribution.visitor("/api/download/queue/**"),
                WebRouteContribution.visitor("/api/batch/**"),
                WebRouteContribution.visitor("/api/download/extensions"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-batch.html", true),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-batch/", "/pixiv-batch/"));
    }

    @Override
    public List<StartupRouteContribution> startupRoutes() {
        // multi 模式默认落点：下载工作台页。
        return List.of(new StartupRouteContribution(ID, "/pixiv-batch.html", 10, Set.of(StartupRouteContext.MULTI)));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 下载工作台跨页入口。VISITOR：multi 匿名访客与管理员在 /api/navigation 可见、受邀访客看不到
        //（下载页对受邀访客 403，故不进其导航栏）。本插件 required，配置写 false 也仍贡献导航（恒进活动快照）。
        // placement：顶部栏 + 各侧栏（含中立主侧栏 app.sidebar）；priority 10 为内置项最小值，使「自带基础页面」
        // 在每个 slot 内恒排最前。标签走本插件自有 namespace batch 的 nav.label。
        return List.of(new NavigationContribution(
                ID,
                Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
                        NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR),
                "batch", "nav.label", "/pixiv-batch.html", "download", AccessPolicy.VISITOR, 10));
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

    @Override
    public List<QueueTypeContribution> queueTypes() {
        // 插画作品类型：下载工作台自有行为模块按与其它类型相同的版本化前端契约注册。
        // 小说等其它类型由各自插件经 queueTypes() 贡献、附带 moduleUrl 指向其行为模块。
        // 插画子模式是下载工作台的基础能力，展示文案由本插件的 batch namespace 提供。
        return List.of(new QueueTypeContribution(
                ID, "illust", "batch", "batch.user.kind-illust", 10,
                "/pixiv-batch/pixiv-queue-type.js",
                new DownloadTypeDescriptor(
                        DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                        ID,
                        "illust",
                        "batch",
                        "batch.user.kind-illust",
                        10,
                        "image",
                        "pixiv",
                        "/pixiv-batch/pixiv-queue-type.js",
                        List.of(
                                DownloadAcquisitionMode.SINGLE_IMPORT,
                                DownloadAcquisitionMode.USER_PROFILE,
                                DownloadAcquisitionMode.SERIES_COLLECTION,
                                DownloadAcquisitionMode.SEARCH,
                                DownloadAcquisitionMode.QUICK),
                        DownloadQueueCapabilities.full(),
                        DownloadScheduleCapabilities.saveableSource(),
                        List.of("illust-extra"),
                        List.of(),
                        List.of(),
                        "batch",
                        DownloadGalleryCapabilities.independentPageOnly())));
    }

    @Override
    public List<TabContribution> downloadTabs() {
        // 获取方式标签页只声明宿主稳定模式与排序；支持的作品类型由各 DownloadTypeDescriptor.acquisitionModes
        // 动态推导，避免下载工作台硬编码其它插件类型。计划任务标签页是管理 UI、不产出队列项，不在此声明。
        return List.of(
                new TabContribution(ID, "quick-fetch", 10, List.of()),
                new TabContribution(ID, "single-import", 20, List.of()),
                new TabContribution(ID, "user", 30, List.of()),
                new TabContribution(ID, "search", 40, List.of()),
                new TabContribution(ID, "series", 50, List.of()));
    }

    @Override
    public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
        return PixivScheduledSourceDescriptors.createAll();
    }
}
