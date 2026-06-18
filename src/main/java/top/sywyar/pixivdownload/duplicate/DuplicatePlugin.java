package top.sywyar.pixivdownload.duplicate;

import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

/**
 * 重复检测插件：基于核心图片 Hash 索引的疑似重复页面、API 与手动重扫入口。
 * Hash 的下载后即时计算与缺失回填属核心资产索引能力，不随本插件禁用。
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

    @Override
    public String displayName() {
        return "重复检测";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 管理员专属：页面与 API 均按 monitor 语义保护（方法不限）。
        return List.of(
                new WebRouteContribution("/pixiv-duplicates.html", AccessPolicy.ADMIN, Set.of(), false),
                new WebRouteContribution("/pixiv-duplicates/**", AccessPolicy.ADMIN, Set.of(), false),
                new WebRouteContribution("/api/duplicates/**", AccessPolicy.ADMIN, Set.of(), false));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(
                ID, "classpath:/static/pixiv-duplicates/", "/pixiv-duplicates/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // namespace 沿用前端 PixivI18n.create 既有名（复数 duplicates），与插件 id 不强求一致。
        // 第三参为 /api/i18n/meta 的全局展示顺序（保持历史 namespace 顺序）。
        return List.of(new I18nContribution("duplicates", "i18n.web.duplicates", 8));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(new NavigationContribution(
                ID, "nav.label", "/pixiv-duplicates.html", "copy", AccessPolicy.ADMIN, 70));
    }
}
