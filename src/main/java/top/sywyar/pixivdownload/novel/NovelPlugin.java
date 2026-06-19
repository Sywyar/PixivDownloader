package top.sywyar.pixivdownload.novel;

import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/**
 * 小说插件：小说画廊/详情页面、小说下载与合订、TTS 与 AI 听书入口。
 * <p>
 * 与画廊插件孪生：画廊 / 详情页全部路由 {@code INVITED_GUEST}（monitor 语义保护 + 受邀访客只读，
 * 双重语义由路由镜像测试逐条守护）。小说画廊 API 与插画共用 {@code /api/gallery}
 * 前缀，但按控制器实际归属各自声明窄前缀：小说占 {@code /api/gallery/novel(s)}，画廊占
 * {@code /api/gallery/artwork(s)} 与 {@code /api/gallery/tags}，互不越界（registry 对相同
 * 三元组拒绝重复登记）。无私有表（novels 系按卸载投影测试归 core）。
 * <p>
 * 核心列使用声明的口径：插件 Bean 收敛范围内（{@code @PluginManagedBean} /
 * 插件 Configuration 装配的类）的直接 SQL 触及面，与 Java 包边界无关——
 * 本插件即 {@link top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository} 触及的表列。
 * 同住 novel 包的 {@code NovelDatabase} / {@code NovelMapper} 是核心机器（novel-core
 * 不强拆），其 schema 由核心 contribution 保证，不入声明。现有查询全部由核心 schema
 * 既有索引承载，无需向核心表补新索引。
 */
public class NovelPlugin implements PixivFeaturePlugin {

    private static final String ID = "novel";

    @Override
    public String id() {
        return ID;
    }

    // 展示名 / 简介在本插件自有 namespace（novel）解析：名称复用已有的导航标签 nav.label，简介用专用 key。
    @Override
    public String displayName() {
        return "nav.label";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 小说页面 + 小说自身的 /api/gallery 子面，全部 INVITED_GUEST：同时进入 monitor 清单与
        // 访客邀请白名单（访客仅 GET/HEAD 的收窄由访问级别语义承载）。小说 API 与插画共用
        // /api/gallery 前缀，按控制器实际归属各占窄前缀：小说占 /api/gallery/novel(s)，画廊占
        // /api/gallery/artwork(s)/tags，互不越界。/api/gallery/novel/** 命中单本小说及其子资源，
        // /api/gallery/novels/** 命中小说列表的子路径；而列表裸端点 /api/gallery/novels（无尾斜杠）
        // 不被去 ** 派生的 startsWith 前缀覆盖，故单列精确声明，使其同样进入 monitor 清单 / 访客白名单
        // （与历史 /api/gallery/** 对该端点的覆盖一致，避免裸列表端点在 multi 模式被匿名访问）。
        // 小说下载端点归小说自有前缀 /api/novel/**（端点迁移见 NovelDownloadController）+ 旧址兼容垫片
        // /api/download/{pixiv/novel,novel/status,novel/translate-status}（NovelDownloadLegacyForwardController
        // forward 至新址）。两者一律 VISITOR：复刻插画下载 /api/download/pixiv 的现状——multi 访客可
        // 下载（走配额）、solo 需会话、邀请访客 403、不入 monitor（AuthFilter 不为该策略派生任何清单、命中后落到
        // 默认会话/访客分支）。声明它只为把这些写端点纳入本插件归属、随启停（禁用 → 新旧小说路径一并 404）。
        return List.of(
                WebRouteContribution.invitedGuest("/pixiv-novel-gallery.html"),
                WebRouteContribution.invitedGuest("/pixiv-novel.html"),
                WebRouteContribution.invitedGuest("/pixiv-novel-gallery/**"),
                WebRouteContribution.invitedGuest("/pixiv-novel/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novel/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novels/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novels"),
                WebRouteContribution.visitor("/api/novel/download"),
                WebRouteContribution.visitor("/api/novel/status/**"),
                WebRouteContribution.visitor("/api/novel/translate-status/**"),
                WebRouteContribution.visitor("/api/download/pixiv/novel"),
                WebRouteContribution.visitor("/api/download/novel/status/**"),
                WebRouteContribution.visitor("/api/download/novel/translate-status/**"),
                // 下载工作台的小说队列类型行为模块（novel-queue-type.js）serving 目录：由下载页（VISITOR）消费，
                // 随小说插件启停。VISITOR：multi 访客可加载 / solo 需会话 / 邀请访客 403 / 不入 monitor。
                WebRouteContribution.visitor("/pixiv-novel-download/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        // pixiv-novel-download/：下载工作台的小说队列类型行为模块（novel-queue-type.js）所在目录。
        // 由小说插件 serving，随插件启停：禁用 → 目录不再注册 → 下载页据 /api/download/extensions 不再加载该模块。
        return List.of(
                new StaticResourceContribution(ID, "classpath:/static/pixiv-novel-gallery/", "/pixiv-novel-gallery/"),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-novel/", "/pixiv-novel/"),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-novel-download/", "/pixiv-novel-download/"));
    }

    @Override
    public List<QueueTypeContribution> queueTypes() {
        // 小说作品类型：下载工作台队列引擎据此多态派发；行为（判重 / 载荷 / 状态轮询 / 系列合订 / 译文轮询）
        // 由 moduleUrl 指向的小说自有行为模块在运行期注册。labelI18nKey 复用现有 kind 单选标签键
        //（子模式单选 DOM 仍在下载页 HTML、由「类型是否启用」统一显隐；标签键位于 novel namespace 是历史现状）。
        return List.of(new QueueTypeContribution(
                ID, "novel", "novel:batch.user.kind-novel", 20, "/pixiv-novel-download/novel-queue-type.js"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 页面跟插件走：小说画廊/详情页的 novel 与 AI 听书的 narration 归本插件；
        // translate（AI 翻译共享文案）留核心 built-in，终局归宿是后续的 AI 翻译插件。
        // 第三参为 /api/i18n/meta 的全局展示顺序（保持历史 namespace 顺序）。
        return List.of(
                new I18nContribution("novel", "i18n.web.novel", 12),
                new I18nContribution("narration", "i18n.web.narration", 14));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(new NavigationContribution(
                ID, "nav.label", "/pixiv-novel-gallery.html", "book", AccessPolicy.INVITED_GUEST, 30));
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
