package top.sywyar.pixivdownload.novel;

import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;
import java.util.Set;

/**
 * 小说插件：小说下载、Pixiv 小说代理、下载工作台小说队列类型、计划任务小说执行器、
 * 小说画廊与阅读页展示能力。
 */
public class NovelPlugin implements PixivFeaturePlugin {

    private static final String ID = "novel";

    @Override
    public String id() {
        return ID;
    }

    // 展示名 / 简介为纯 i18n key；namespace 由 displayNamespace() 默认取本插件首个 namespace（novel）。
    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射）：小说。
    @Override
    public String iconKey() {
        return "book";
    }

    @Override
    public String colorToken() {
        return "amber";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 小说下载端点归小说自有前缀 /api/novel/**（端点迁移见 NovelDownloadController）+ 旧址兼容垫片
        // /api/download/{pixiv/novel,novel/status,novel/translate-status}（NovelDownloadLegacyForwardController
        // forward 至新址）。两者一律 VISITOR：复刻插画下载 /api/download/pixiv 的现状——multi 访客可
        // 下载（走配额）、solo 需会话、邀请访客 403、不入 monitor（AuthFilter 不为该策略派生任何清单、命中后落到
        // 默认会话/访客分支）。声明它只为把这些写端点纳入本插件归属、随启停（禁用 → 新旧小说路径一并 404）。
        return List.of(
                WebRouteContribution.visitor("/api/novel/download"),
                WebRouteContribution.visitor("/api/novel/status/**"),
                WebRouteContribution.visitor("/api/novel/translate-status/**"),
                WebRouteContribution.visitor("/api/novel/*/downloaded"),
                WebRouteContribution.visitor("/api/novel/series/*/merge"),
                WebRouteContribution.visitorAndInvitedGuest("/api/novel/series/*/merged"),
                WebRouteContribution.admin("/api/novel/*/translate"),
                WebRouteContribution.admin("/api/novel/translate-lang-probe"),
                WebRouteContribution.admin("/api/novel/series/*/translate-title"),
                WebRouteContribution.admin("/api/novel/series/*/novel-ids"),
                WebRouteContribution.admin("/api/narration/**"),
                WebRouteContribution.visitorAndInvitedGuest("/api/pixiv/novel/**"),
                WebRouteContribution.visitor("/api/pixiv/novel-search**"),
                WebRouteContribution.visitor("/api/pixiv/user/*/novels"),
                WebRouteContribution.visitor("/api/pixiv/user/*/novel-cards"),
                WebRouteContribution.visitor("/api/pixiv/me/novel-bookmarks"),
                WebRouteContribution.visitor("/api/download/pixiv/novel"),
                WebRouteContribution.visitor("/api/download/novel/status/**"),
                WebRouteContribution.visitor("/api/download/novel/translate-status/**"),
                WebRouteContribution.invitedGuest("/pixiv-novel-gallery.html"),
                WebRouteContribution.invitedGuest("/pixiv-novel.html"),
                WebRouteContribution.invitedGuest("/pixiv-novel-gallery/**"),
                WebRouteContribution.invitedGuest("/pixiv-novel/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novel/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novels/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novels"),
                // 下载工作台的小说队列类型行为模块（novel-queue-type.js）serving 目录：由下载页（VISITOR）消费，
                // 随小说插件启停。VISITOR：multi 访客可加载 / solo 需会话 / 邀请访客 403 / 不入 monitor。
                WebRouteContribution.visitor("/pixiv-novel-download/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        // pixiv-novel-download/：下载工作台的小说队列类型行为模块（novel-queue-type.js）所在目录。
        // 由小说插件 serving，随插件启停：禁用 → 目录不再注册 → 下载页据 /api/download/extensions 不再加载该模块。
        return List.of(
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-novel-download/", "/pixiv-novel-download/"),
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-novel-gallery.html", true),
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-novel.html", true),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-novel-gallery/",
                        "/pixiv-novel-gallery/"),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-novel/", "/pixiv-novel/"));
    }

    @Override
    public List<QueueTypeContribution> queueTypes() {
        // 小说作品类型：下载工作台队列引擎据此多态派发；行为（判重 / 载荷 / 状态轮询 / 系列合订 / 译文轮询）
        // 由 moduleUrl 指向的小说自有行为模块在运行期注册。labelI18nKey 复用现有 kind 单选标签键
        //（子模式单选 DOM 仍在下载页 HTML、由「类型是否启用」统一显隐；标签键位于 novel namespace 是历史现状）。
        return List.of(new QueueTypeContribution(
                ID, "novel", "novel", "batch.user.kind-novel", 20, "/pixiv-novel-download/novel-queue-type.js"));
    }

    /** 下载页 novel 队列类型行为模块的 serving URL（同时渲染下面声明的各 UI 槽位）。 */
    private static final String NOVEL_MODULE_URL = "/pixiv-novel-download/novel-queue-type.js";

    /**
     * 下载页 novel 作品类型向宿主贡献的 UI 槽位锚点（宿主页以 {@code <template data-qt-slot="<target>">} 声明）。
     * 各锚点的实际 DOM 片段由 {@link #NOVEL_MODULE_URL} 指向的行为模块渲染；此处声明「哪些锚点、哪个模块渲染、
     * 何顺序」的后端契约，使小说插件禁用 / 停用时这些槽位从扩展点快照消失、下载页对应入口随之缺席。
     */
    private static final List<String> NOVEL_UI_SLOT_TARGETS = List.of(
            "kind-option-user", "kind-option-search", "kind-option-quick",
            "quick-actions-bookmarks", "quick-actions-mine",
            "import-hint", "search-filter", "settings-card");

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return NOVEL_UI_SLOT_TARGETS.stream()
                .map(target -> new WebUiSlotContribution(
                        ID, ID + "." + target, target, NOVEL_MODULE_URL, 20))
                .toList();
    }

    @Override
    public List<I18nContribution> i18n() {
        // novel namespace 保留给下载工作台小说队列、计划任务摘要与小说核心交互；
        // novel-gallery namespace 保留给小说画廊与阅读页文案。
        return List.of(
                new I18nContribution("novel", "i18n.web.novel", 12),
                new I18nContribution("novel-gallery", "i18n.web.novel-gallery", 12),
                new I18nContribution("narration", "i18n.web.narration", 14));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(
                new NavigationContribution(
                        "novel-gallery",
                        Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.NOVEL_SIDEBAR),
                        "novel-gallery", "nav.label", "/pixiv-novel-gallery.html?view=all", "book",
                        AccessPolicy.INVITED_GUEST, 40),
                new NavigationContribution(
                        "novel-type-switch",
                        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
                        "novel-gallery", "nav.type-novel", "/pixiv-novel-gallery.html?view=all", "book",
                        AccessPolicy.INVITED_GUEST, 40));
    }

    @Override
    public List<LandingContribution> landings() {
        return List.of(new LandingContribution(
                ID, "novel-gallery", Audience.INVITED_GUEST, "/pixiv-novel-gallery.html", 30));
    }

    @Override
    public List<CoreColumnUsage> coreColumnUsages() {
        return List.of(
                new CoreColumnUsage("novels", List.of(
                        "novel_id", "author_id", "R18", "time", "deleted",
                        "series_id", "series_order")),
                new CoreColumnUsage("novel_tags", List.of("novel_id", "tag_id")),
                new CoreColumnUsage("tags", List.of("tag_id", "name", "translated_name")));
    }
}
