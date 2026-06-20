package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.schedule.source.CollectionSource;
import top.sywyar.pixivdownload.schedule.source.FollowLatestSource;
import top.sywyar.pixivdownload.schedule.source.MyBookmarksSource;
import top.sywyar.pixivdownload.schedule.source.SearchSource;
import top.sywyar.pixivdownload.schedule.source.SeriesSource;
import top.sywyar.pixivdownload.schedule.source.UserNewSource;
import top.sywyar.pixivdownload.schedule.source.UserRequestSource;

import java.util.List;
import java.util.Set;

/**
 * 下载工作台插件：{@code pixiv-batch} 页面、下载队列、油猴脚本入口与下载执行，
 * 并收编计划任务能力（scheduler 引擎 + {@code /api/schedule/**} 路由 + 下载域计划任务来源）。
 * <p>
 * 计划任务归本插件子能力、不独立成插件（UI 焊进下载页、本插件是 required 插件）；
 * {@code ScheduledSourceProvider} SPI 保留，其他插件仍可经 {@code scheduledSources()} 贡献来源。
 * {@code scheduled_tasks} 表仍归核心（卸载投影 + 核心数据不受插件开关影响），故其 schema 由核心
 * contribution 保证、不入本插件声明；schedule 引擎对 {@code scheduled_tasks} 的访问全经核心 owned 语义 Store
 * {@code ScheduledTaskStore}（核心实现层把 MyBatis mapper 收拢在 {@code core.schedule.db} 之后），收编的业务
 * Bean 只依赖该核心接口、自身不写直接 SQL，故本插件无核心列使用声明。
 * <p>
 * 横切 / 共享的下载数据 API（{@code /api/download/status*}、{@code /api/downloaded/*} 统计 / 历史 /
 * 图片字节 serving、本地放行特例）由核心声明：它们被核心 monitor 页与画廊等其它页面跨插件消费，
 * 留核心避免禁用本插件时裂页（与图片字节 serving 迁核心同一原则）。
 */
public class DownloadWorkbenchPlugin implements PixivFeaturePlugin {

    private static final String ID = "download-workbench";

    @Override
    public String id() {
        return ID;
    }

    // 下载工作台必选、永不在配置页「插件」分组呈现，故下列 key 不会被解析（仅为满足契约的占位）。
    @Override
    public String displayName() {
        return "plugin.label";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    /** 下载工作台是必选插件：下载页、下载执行与计划任务调度是核心使用路径，不允许关闭。 */
    @Override
    public boolean required() {
        return true;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 计划任务管理 API：仅管理员（solo / multi 均仅 monitor），随本插件收编的 schedule 能力声明。
        // 下载页与其提交 / 队列 / 状态 API：下载页 /pixiv-batch.html、其拆分静态目录 /pixiv-batch/**，以及
        // 下载提交（/api/download/pixiv）、取消（/api/cancel/**、/api/download/cancel/**）、队列清理
        //（/api/download/queue/**）、批量状态（/api/batch/**）、扩展点装配（/api/download/extensions）一律
        // VISITOR——复刻现状「未受管页面 / 未声明 API」的涌现行为：multi 访客可达（走配额） / solo 需会话 /
        // 邀请访客 403 / 不入 monitor。AuthFilter 不为 VISITOR 派生任何清单、命中后落默认会话 / 访客分支，
        // 访问行为与未声明时逐字等价；声明只为消除「未声明路由」歧义、纳入路由归属与全 URL 声明守卫，随插件启停。
        // 其它跨插件共享的下载数据 API（status/downloaded 统计 / 历史 / 图片字节）由核心声明（见类注释）。
        return List.of(
                WebRouteContribution.admin("/api/schedule/**"),
                WebRouteContribution.visitor("/pixiv-batch.html"),
                WebRouteContribution.visitor("/pixiv-batch/**"),
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
                new StaticResourceContribution(ID, "classpath:/static/pixiv-batch/", "/pixiv-batch/"));
    }

    @Override
    public List<StartupRouteContribution> startupRoutes() {
        // multi 模式默认落点：下载工作台页（/redirect 在 multi 模式以本插件为首选）。
        return List.of(new StartupRouteContribution(ID, "/pixiv-batch.html", 10));
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
                "batch:nav.label", "/pixiv-batch.html", "download", AccessPolicy.VISITOR, 10));
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
        // 插画作品类型：下载工作台内置默认类型，行为由宿主队列引擎内联注册（moduleUrl 为 null、无需外部模块）。
        // 小说等其它类型由各自插件经 queueTypes() 贡献、附带 moduleUrl 指向其行为模块。
        return List.of(new QueueTypeContribution(
                ID, "illust", "novel:batch.user.kind-illust", 10, null));
    }

    @Override
    public List<TabContribution> downloadTabs() {
        // 获取方式标签页（acquisition 轴）：下载页内置的五种找作品方式。各声明能产出的作品类型，
        // 下载页把「标签页支持的类型 ∩ 已启用类型」渲染为子模式（kind 单选）——某类型插件禁用时其选项
        // 在所有标签页自动消失。计划任务标签页是管理 UI、不产出队列项，不在此声明。
        return List.of(
                new TabContribution(ID, "quick-fetch", 10, List.of("illust", "novel")),
                new TabContribution(ID, "single-import", 20, List.of("illust", "novel")),
                new TabContribution(ID, "user", 30, List.of("illust", "novel")),
                new TabContribution(ID, "search", 40, List.of("illust", "novel")),
                new TabContribution(ID, "series", 50, List.of("illust", "novel")));
    }

    @Override
    public List<ScheduledSourceProvider> scheduledSources() {
        // 现有 7 个计划任务来源随 schedule 能力收编进下载工作台声明，跨插画 / 小说统一调度。每个来源是一个
        // ScheduledSource（在 plugin.api 身份 SPI 之上附加发现 / 模式 / 谓词执行行为），调度器经来源注册中心
        // 解析后直接派发——调度主编排不再按类型枚举 switch 调具体来源实现。其他插件仍可经各自
        // scheduledSources() 贡献新来源。
        return List.of(
                new UserNewSource(),
                new UserRequestSource(),
                new SearchSource(),
                new SeriesSource(),
                new MyBookmarksSource(),
                new FollowLatestSource(),
                new CollectionSource());
    }
}
