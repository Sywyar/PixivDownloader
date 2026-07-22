package top.sywyar.pixivdownload.douyin;

import top.sywyar.pixivdownload.douyin.db.history.DouyinSchemaContribution;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceDescriptors;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class DouyinPlugin implements PixivFeaturePlugin {

    public static final String ID = "douyin";
    private static final String GUI_GROUP_ID = "douyin";
    private static final String MODULE_URL = "/pixiv-douyin-download/douyin-queue-type.js";
    private static final List<String> UI_SLOT_TARGETS = List.of(
            "kind-option-quick",
            "kind-option-user",
            "quick-actions-bookmarks",
            "quick-actions-mine",
            "import-hint",
            "cookie-tools");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public String iconKey() {
        return "video";
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
    public List<SchemaContribution> schema() {
        return List.of(DouyinSchemaContribution.CONTRIBUTION);
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.admin("/pixiv-douyin-gallery.html"),
                WebRouteContribution.admin("/pixiv-douyin-gallery/**"),
                WebRouteContribution.admin("/pixiv-douyin.html"),
                WebRouteContribution.admin("/pixiv-douyin/**"),
                WebRouteContribution.admin("/api/douyin/gallery/**"),
                WebRouteContribution.admin("/api/douyin/history/**"),
                WebRouteContribution.visitor("/api/douyin/me/favorite-folders"),
                WebRouteContribution.visitor("/api/douyin/me/favorite-folders/**"),
                WebRouteContribution.visitor("/api/douyin/**"),
                WebRouteContribution.visitor("/pixiv-douyin-download/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(
                        ID, "classpath:/static/", "/pixiv-douyin-gallery.html", true),
                new StaticResourceContribution(
                        ID, "classpath:/static/", "/pixiv-douyin.html", true),
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-douyin-gallery/", "/pixiv-douyin-gallery/"),
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-douyin/", "/pixiv-douyin/"),
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-douyin-download/", "/pixiv-douyin-download/"));
    }

    @Override
    public List<DownloadTypeDescriptor> downloadTypes() {
        return List.of(new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                ID,
                "douyin",
                "batch.kind",
                30,
                "video",
                "red",
                MODULE_URL,
                List.of(
                        DownloadAcquisitionMode.SINGLE_IMPORT,
                        DownloadAcquisitionMode.USER_PROFILE,
                        DownloadAcquisitionMode.SEARCH,
                        DownloadAcquisitionMode.SERIES_COLLECTION,
                        DownloadAcquisitionMode.QUICK),
                true,
                List.of(),
                List.of(),
                "douyin"));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return UI_SLOT_TARGETS.stream()
                .map(target -> new WebUiSlotContribution(ID, ID + "." + target, target, MODULE_URL, 30))
                .toList();
    }

    @Override
    public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
        return DouyinScheduledSourceDescriptors.createAll();
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        List<GuiConfigFieldContribution> fields = List.of(
                path(DouyinPluginSettingsService.KEY_DOWNLOAD_DIRECTORY,
                        "gui.config.field.douyin.download.directory", 10),
                proxyMode(),
                proxyHost(),
                proxyPort(),
                includeCover());
        return List.of(new GuiConfigContribution(
                List.of(new GuiConfigGroupContribution(
                        GUI_GROUP_ID, "settings.download.title", ID, 1600, true)),
                fields));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution("douyin", "i18n.web.douyin", 18));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(new NavigationContribution(
                "douyin-gallery-type-switch",
                Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
                "douyin", "nav.gallery", "/pixiv-douyin-gallery.html?view=all", "video",
                AccessPolicy.ADMIN, 50));
    }

    private static GuiConfigFieldContribution path(String key, String keyPrefix, int order) {
        return new GuiConfigFieldContribution(
                key,
                GUI_GROUP_ID,
                keyPrefix + ".label",
                keyPrefix + ".help",
                ID,
                GuiConfigFieldType.PATH_DIR,
                "",
                order,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
    }

    private static GuiConfigFieldContribution proxyMode() {
        return new GuiConfigFieldContribution(
                DouyinPluginSettingsService.KEY_PROXY_MODE,
                GUI_GROUP_ID,
                "gui.config.field.douyin.proxy.mode.label",
                "gui.config.field.douyin.proxy.mode.help",
                ID,
                GuiConfigFieldType.ENUM,
                "inherit",
                20,
                false,
                false,
                List.of("inherit", "proxy", "custom", "direct"),
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(
                        "inherit", "gui.config.field.douyin.proxy.mode.value.inherit",
                        "proxy", "gui.config.field.douyin.proxy.mode.value.proxy",
                        "custom", "gui.config.field.douyin.proxy.mode.value.custom",
                        "direct", "gui.config.field.douyin.proxy.mode.value.direct"));
    }

    private static GuiConfigFieldContribution proxyHost() {
        return customProxyField(
                DouyinPluginSettingsService.KEY_PROXY_HOST,
                "gui.config.field.douyin.proxy.host",
                GuiConfigFieldType.STRING,
                30);
    }

    private static GuiConfigFieldContribution proxyPort() {
        return customProxyField(
                DouyinPluginSettingsService.KEY_PROXY_PORT,
                "gui.config.field.douyin.proxy.port",
                GuiConfigFieldType.PORT,
                40);
    }

    private static GuiConfigFieldContribution includeCover() {
        return new GuiConfigFieldContribution(
                DouyinPluginSettingsService.KEY_INCLUDE_COVER,
                GUI_GROUP_ID,
                "gui.config.field.douyin.include-cover.label",
                "gui.config.field.douyin.include-cover.help",
                ID,
                GuiConfigFieldType.BOOL,
                "false",
                50,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
    }

    private static GuiConfigFieldContribution customProxyField(String key,
                                                               String keyPrefix,
                                                               GuiConfigFieldType type,
                                                               int order) {
        return new GuiConfigFieldContribution(
                key,
                GUI_GROUP_ID,
                keyPrefix + ".label",
                keyPrefix + ".help",
                ID,
                type,
                "",
                order,
                false,
                false,
                List.of(),
                List.of(),
                List.of(GuiConfigCondition.equalsTo(DouyinPluginSettingsService.KEY_PROXY_MODE, "custom")),
                null,
                null);
    }
}
