package top.sywyar.pixivdownload.duplicate;

import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

/**
 * 重复检测插件：基于核心图片 Hash 索引的疑似重复页面、API 与手动重扫入口。
 * Hash 的下载后即时计算属宿主核心资产索引能力，不属本插件、不随本插件禁用；而缺失 Hash 的批量回填维护任务
 * （{@link DuplicateHashBackfillTask}）随本插件归属、
 * 经 {@link #maintenanceTasks()} 声明，禁用本插件时维护窗口跳过它、重新启用后恢复（只补齐历史缺口
 * 与失败哨兵，下载期已即时记录的 Hash 无需重抓）。
 * <p>
 * 疑似重复检测是管理员专属功能（{@code ADMIN}，即 solo 会话用户或 multi 登录管理员），
 * 不得进入 isPublic / 访客邀请白名单——该不变量由路由镜像测试守护。无私有表
 * （{@code artwork_image_hashes} 含 page=-1 哨兵语义，按卸载投影测试归 core）。
 */
public class DuplicatePlugin implements PixivFeaturePlugin {

    private static final String ID = "duplicate";

    @Override
    public String id() {
        return ID;
    }

    // 展示名 / 简介为纯 i18n key；namespace 由 displayNamespace() 默认取本插件首个 namespace（duplicates）：导航标签
    // 是「疑似重复」、与插件名「重复检测」不同，故名称用专用 key（不复用 nav.label），简介用专用 key。
    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射）：疑似重复检测。
    @Override
    public String iconKey() {
        return "duplicate";
    }

    @Override
    public String colorToken() {
        return "red";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 管理员专属：页面与 API 均按 monitor 语义保护（方法不限）。
        return List.of(
                WebRouteContribution.admin("/pixiv-duplicates.html"),
                WebRouteContribution.admin("/pixiv-duplicates/**"),
                WebRouteContribution.admin("/api/duplicates/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-duplicates.html", true),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-duplicates/", "/pixiv-duplicates/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // namespace 沿用前端 PixivI18n.create 既有名（复数 duplicates），与插件 id 不强求一致。
        // 第三参为 /api/i18n/meta 的全局展示顺序（保持历史 namespace 顺序）。
        return List.of(new I18nContribution("duplicates", "i18n.web.duplicates", 8));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 疑似重复入口：管理员可见（ADMIN）。placement——顶部栏 + 各侧栏主导航（含中立主侧栏 app.sidebar）。
        // priority 60：功能页面区段末位。本页顶部自身的图标入口区（画廊 / 统计）由画廊 / 统计插件向
        // duplicates.header-icons 贡献，不在此声明。
        return List.of(new NavigationContribution(
                ID,
                Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
                        NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR),
                "duplicates", "nav.label", "/pixiv-duplicates.html", "copy", AccessPolicy.ADMIN, 60));
    }

    @Override
    public List<Class<? extends MaintenanceTask>> maintenanceTasks() {
        // 缺失 Hash 批量回填任务随本插件启停：禁用 duplicate 时维护窗口跳过它（下载期即时算 Hash 的
        // 核心链路不受影响），重新启用后恢复、只补齐历史缺口与失败哨兵。
        return List.of(DuplicateHashBackfillTask.class);
    }
}
