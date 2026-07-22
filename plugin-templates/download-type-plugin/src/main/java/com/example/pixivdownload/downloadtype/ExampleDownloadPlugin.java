package com.example.pixivdownload.downloadtype;

import com.example.pixivdownload.downloadtype.schedule.ExampleScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;

/** Stable contribution declarations for the example download type. */
public final class ExampleDownloadPlugin implements PixivFeaturePlugin {

    public static final String ID = "example-download";
    public static final String TYPE = "example-download";
    public static final String NAMESPACE = "example-download";
    public static final String TYPE_MODULE_URL = "/example-download/example-download-type.js";
    public static final String UI_MODULE_URL = "/example-download/example-download-ui-slot.js";

    private static final List<String> UI_SLOT_TARGETS = List.of("settings-card", "quick-actions-mine");

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
        return "download";
    }

    @Override
    public String colorToken() {
        return "green";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.visitor("/api/example-download/**"),
                WebRouteContribution.admin("/api/example-download/gallery"),
                WebRouteContribution.visitor("/example-download/**"),
                WebRouteContribution.admin("/example-download-gallery.html"),
                WebRouteContribution.admin("/example-download-gallery/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(
                        ID, "classpath:/static/example-download/", "/example-download/"),
                new StaticResourceContribution(
                        ID, "classpath:/static/", "/example-download-gallery.html", true),
                new StaticResourceContribution(
                        ID, "classpath:/static/example-download-gallery/", "/example-download-gallery/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(NAMESPACE, "i18n.web.example-download"));
    }

    @Override
    public List<DownloadTypeDescriptor> downloadTypes() {
        return List.of(new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                TYPE,
                NAMESPACE,
                "batch.kind",
                900,
                "download",
                "green",
                TYPE_MODULE_URL,
                List.of(
                        DownloadAcquisitionMode.SINGLE_IMPORT,
                        DownloadAcquisitionMode.USER_PROFILE,
                        DownloadAcquisitionMode.SERIES_COLLECTION,
                        DownloadAcquisitionMode.SEARCH,
                        DownloadAcquisitionMode.QUICK),
                true,
                List.of("example-ready-filter"),
                List.of("example-output-setting"),
                NAMESPACE));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return List.of(
                new WebUiSlotContribution(
                        ID, ID + ".settings-card", "settings-card", TYPE_MODULE_URL, 900),
                new WebUiSlotContribution(
                        ID, ID + ".quick-actions-mine", "quick-actions-mine", UI_MODULE_URL, 900));
    }

    @Override
    public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
        return List.of(ExampleScheduledSourceExecutor.descriptor());
    }
}
