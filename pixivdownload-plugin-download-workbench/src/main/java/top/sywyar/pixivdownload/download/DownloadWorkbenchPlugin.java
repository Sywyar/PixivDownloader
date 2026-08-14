package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
    private static final String PREFERRED_DOWNLOAD_WORKBENCH_MARKER = "preferred-download-workbench";
    private static final String SURVEY_PUBLICATION_RESOURCE =
            "static/pixiv-layout-feedback/release-publication.properties";
    private static final String SURVEY_INSTANCE_KEY = "layout-feedback-v1";
    private static final String SURVEY_EMBED_URL = "/pixiv-layout-feedback/embed.html"
            + "?pixivBridgeGet=/api/i18n/meta"
            + "&pixivBridgeGet=/api/i18n/messages/layout-feedback"
            + "&pixivBridgeGet=/api/app/info"
            + "&pixivBridgeGet=/api/layout-feedback/state"
            + "&pixivBridgePost=/api/layout-feedback/state"
            + "&pixivBridgeRead=pixiv_theme"
            + "&pixivBridgeRead=pixiv:batch-layout:v1"
            + "&pixivBridgeRead=pixiv:layout-feedback:state:v1"
            + "&pixivBridgeRead=pixiv:layout-feedback:seen:v1"
            + "&pixivBridgeRead=pixivdownload.posthog.survey-id.%5B%22download-workbench.layout-feedback"
            + "%22%2C%22019fce31-c9ce-0000-934a-375b3ddbbd6c%22%5D"
            + "&pixivBridgeWrite=pixiv:layout-feedback:state:v1"
            + "&pixivBridgeWrite=pixiv:layout-feedback:seen:v1"
            + "&pixivBridgeWrite=pixivdownload.posthog.survey-id.%5B%22download-workbench.layout-feedback"
            + "%22%2C%22019fce31-c9ce-0000-934a-375b3ddbbd6c%22%5D";

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

    @Override
    public List<WebRouteContribution> routes() {
        // 下载页与其提交 / 队列 / 状态 API：下载页 /pixiv-batch.html、其拆分静态目录 /pixiv-batch/**，以及
        // 下载提交（/api/download/pixiv）、历史取消墓碑（/api/cancel/**、/api/download/cancel/**）、
        // 精确取消与队列清理（/api/download/queue/**）、批量状态（/api/batch/**）、扩展点装配
        //（/api/download/extensions）一律
        // VISITOR——复刻现状「未受管页面 / 未声明 API」的涌现行为：multi 访客可达（走配额） / solo 需会话 /
        // 邀请访客 403 / 不入 monitor。AuthFilter 不为 VISITOR 派生任何清单、命中后落默认会话 / 访客分支，
        // 访问行为与未声明时逐字等价；声明只为消除「未声明路由」歧义、纳入路由归属与全 URL 声明守卫。
        return List.of(
                WebRouteContribution.visitor("/pixiv-batch.html"),
                WebRouteContribution.visitor("/pixiv-batch/**"),
                WebRouteContribution.visitor("/pixiv-batch-alt.html"),
                WebRouteContribution.visitor("/pixiv-batch-alt/**"),
                WebRouteContribution.admin("/pixiv-layout-feedback/embed.html"),
                WebRouteContribution.visitor("/pixiv-layout-feedback/**"),
                new WebRouteContribution("/api/layout-feedback/state", AccessPolicy.VISITOR,
                        Set.of(HttpMethod.GET, HttpMethod.POST), false),
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
                new StaticResourceContribution("classpath:/static/", "/pixiv-batch.html", true),
                new StaticResourceContribution("classpath:/static/pixiv-batch/", "/pixiv-batch/"),
                new StaticResourceContribution("classpath:/static/", "/pixiv-batch-alt.html", true),
                new StaticResourceContribution("classpath:/static/pixiv-batch-alt/", "/pixiv-batch-alt/"),
                new StaticResourceContribution("classpath:/static/pixiv-layout-feedback/", "/pixiv-layout-feedback/"));
    }

    @Override
    public List<StartupRouteContribution> startupRoutes() {
        // multi 模式默认落点：下载工作台页。
        return List.of(new StartupRouteContribution("/pixiv-batch.html", 10, Set.of(StartupRouteContext.MULTI)));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 下载工作台跨页入口。VISITOR：multi 匿名访客与管理员在 /api/navigation 可见、受邀访客看不到
        //（下载页对受邀访客 403，故不进其导航栏）。宿主发行策略将本插件列为必选，配置写 false 也仍贡献导航。
        // placement：顶部栏 + 各侧栏（含中立主侧栏 app.sidebar）；priority 10 让该官方基础页面按既定顺序
        // 在每个 slot 内靠前展示。标签走本插件自有 namespace batch 的 nav.label。
        return List.of(new NavigationContribution(
                ID,
                Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
                        NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR),
                "batch", "nav.label", "/pixiv-batch.html", "download", AccessPolicy.VISITOR, 10,
                Set.of(PREFERRED_DOWNLOAD_WORKBENCH_MARKER)));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 页面跟插件走：下载工作台页面（batch）与油猴脚本分发文案（userscript）归本插件。
        // 第三参为 /api/i18n/meta 的全局展示顺序（保持历史 namespace 顺序）。
        return List.of(
                new I18nContribution("batch", "i18n.web.batch", 5),
                new I18nContribution("batch-alt", "i18n.web.batch-alt", 6),
                new I18nContribution("userscript", "i18n.web.userscript", 16),
                new I18nContribution("layout-feedback", "i18n.web.layout-feedback", 15));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        if (!officialSurveyRelease()) {
            return List.of();
        }
        return List.of(new WebUiSlotContribution(
                "download-workbench.layout-survey",
                "notification.inbox",
                null,
                10,
                Map.of(
                        "notification.category", "survey",
                        "notification.instance-key", SURVEY_INSTANCE_KEY,
                        "notification.embed-url", SURVEY_EMBED_URL,
                        "notification.i18n-namespace", "layout-feedback",
                        "notification.title-key", "layout-feedback.inbox-title",
                        "notification.body-key", "layout-feedback.inbox-body")));
    }

    @Override
    public List<UserscriptContribution> userscripts() {
        // 稳定安装 id 与精确资源均归下载工作台声明；宿主只经本插件 ClassLoader 物化目录，
        // 不按这些私有文件名写分支。
        return List.of(
                userscript("all-in-one", "Pixiv All-in-One.user.js"),
                userscript("artwork-java", "Pixiv 单作品图片下载器(Java后端版).user.js"),
                userscript("artwork-local", "Pixiv 单作品图片下载器(Local Download).user.js"),
                userscript("user-batch", "Pixiv User 批量下载器(User Batch).user.js"),
                userscript("page-batch", "Pixiv 页面批量下载器(Page Scrape).user.js"),
                userscript("import-batch", "Pixiv URL 批量导入单作品下载器(URL Batch).user.js"),
                userscript("experience-toolbox", "Pixiv 体验增强工具箱(Toolbox).user.js"));
    }

    private static UserscriptContribution userscript(String id, String fileName) {
        return new UserscriptContribution(id, "classpath:/static/userscripts/" + fileName);
    }

    private static boolean officialSurveyRelease() {
        Properties properties = new Properties();
        try (InputStream input = DownloadWorkbenchPlugin.class.getClassLoader()
                .getResourceAsStream(SURVEY_PUBLICATION_RESOURCE)) {
            if (input == null) {
                return false;
            }
            properties.load(input);
            return "true".equalsIgnoreCase(properties.getProperty("officialReleaseEnabled"));
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public List<DownloadTypeDescriptor> downloadTypes() {
        // 插画作品类型：下载工作台自有行为模块按与其它类型相同的版本化前端契约注册。
        // 小说等其它类型由各自插件经 downloadTypes() 贡献、附带 moduleUrl 指向其行为模块。
        // 插画子模式是下载工作台的基础能力，展示文案由本插件的 batch namespace 提供。
        return List.of(new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
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
                true,
                List.of("illust-extra"),
                List.of(),
                "batch"));
    }

    @Override
    public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
        return PixivScheduledSourceDescriptors.createAll();
    }
}
