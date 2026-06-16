package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.web.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.schedule.source.EnumScheduledSourceProvider;

import java.util.List;
import java.util.Set;

/**
 * 下载工作台插件：{@code pixiv-batch} 页面、下载队列、油猴脚本入口与下载执行，
 * 并收编计划任务能力（scheduler 引擎 + {@code /api/schedule/**} 路由 + 下载域计划任务来源）。
 * <p>
 * 计划任务归本插件子能力、不独立成插件（UI 焊进下载页、本插件是 required 插件）；
 * {@code ScheduledSourceProvider} SPI 保留，其他插件仍可经 {@code scheduledSources()} 贡献来源。
 * {@code scheduled_tasks} 表仍归核心（卸载投影 + 核心数据不受插件开关影响），故其 schema 由核心
 * contribution 保证、不入本插件声明；schedule 引擎对 {@code scheduled_tasks} 的访问全经根包扫描的
 * MyBatis {@code ScheduledTaskMapper}（核心机器，与 {@code ArtworkDownloadExecutor} 同口径不计入
 * {@code coreColumnUsages}），收编的业务 Bean 自身不写直接 SQL，故本插件无核心列使用声明。
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

    @Override
    public String displayName() {
        return "下载工作台";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 计划任务管理 API：仅管理员（solo / multi 均仅 monitor），随本插件收编的 schedule 能力声明。
        // 下载页 /pixiv-batch.html 与 /pixiv-batch/** 资源沿用「未受管页面」语义（multi 黑名单放行、
        // solo 仅管理员），不声明访问级别以保持现状；其它下载数据 API 是跨插件共享、由核心声明（见类注释）。
        return List.of(
                new WebRouteContribution("/api/schedule/**", AccessLevel.ADMIN_OR_SOLO, Set.of(), false));
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
        // 现有 7 个计划任务来源（USER_NEW / USER_REQUEST / SEARCH / SERIES / MY_BOOKMARKS /
        // FOLLOW_LATEST / COLLECTION）随 schedule 能力收编进下载工作台声明，跨插画 / 小说统一调度。
        // 各 provider 仅承载身份 + legacy 类型映射，发现 / 派发语义由调度器的枚举分支承载。
        // 其他插件（如未来的小说定时下载）仍可经各自 scheduledSources() 贡献新来源。
        return EnumScheduledSourceProvider.builtIn();
    }
}
