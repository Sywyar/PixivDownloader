package top.sywyar.pixivdownload.translation;

import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.translation.api.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;

public class TranslationPlugin implements PixivFeaturePlugin {
    public static final String ID = "translation";
    @Override public List<top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution> privateSchema() {
        return List.of(TranslationJobStore.schema());
    }
    private static final List<GuiConfigContribution> GUI_CONFIG = createGuiConfig();
    public String id() { return ID; }
    public String displayName() { return "plugin.name"; }
    public String description() { return "plugin.summary"; }
    public String displayNamespace() { return "translate"; }
    public PluginKind kind() { return PluginKind.FEATURE; }
    public String iconKey() { return "book"; }
    public String colorToken() { return "teal"; }
    public List<ServiceKey<?>> serviceImports() { return List.of(TranslationDocuments.KEY); }
    public List<ServiceExport<?>> serviceExports() {
        return List.of(new ServiceExport<>(new ServiceKey<>(NovelTranslationDocuments.CONTRACT_ID,
                NovelTranslationOperations.class), "translationOperations"));
    }
    public List<WebRouteContribution> routes() {
        return List.of(WebRouteContribution.admin("/api/translation/**"),
                WebRouteContribution.visitorAndInvitedGuest("/pixiv-translation/**"));
    }
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution("classpath:/static/pixiv-translation/", "/pixiv-translation/"));
    }
    public List<WebUiSlotContribution> uiSlots() {
        return List.of(new WebUiSlotContribution(ID + ".novel-detail", "novel-detail-translate",
                "/pixiv-translation/novel-detail-translate-slot.js", 20),
                new WebUiSlotContribution(ID + ".series-detail", "series-detail-translate",
                "/pixiv-translation/series-detail-translate-slot.js", 20),
                new WebUiSlotContribution(ID + ".download-settings", "settings-card",
                "/pixiv-translation/download-novel-translate-settings-slot.js", 20, Map.of(), "translate"));
    }
    public List<I18nContribution> i18n() { return List.of(new I18nContribution("translate", "i18n.web.translate", 13)); }
    public List<GuiConfigContribution> guiConfigContributions() {
        return GUI_CONFIG;
    }

    private static List<GuiConfigContribution> createGuiConfig() {
        var youdao = GuiConfigCondition.equalsTo("translation.engine", "youdao");
        var libre = GuiConfigCondition.equalsTo("translation.engine", "libretranslate");
        var connection = List.of(
                field("translation.engine", GuiConfigFieldType.ENUM, "ai", 100, List.of("ai", "youdao", "libretranslate")),
                field("translation.youdao-app-key", GuiConfigFieldType.STRING, "", 110, List.of(), youdao).requiredWhen(youdao),
                field("translation.youdao-app-secret", GuiConfigFieldType.PASSWORD, "", 120, List.of(), youdao).requiredWhen(youdao),
                field("translation.libretranslate-url", GuiConfigFieldType.STRING, "", 130, List.of(), libre).requiredWhen(libre),
                field("translation.libretranslate-api-key", GuiConfigFieldType.PASSWORD, "", 140, List.of(), libre));
        var requests = new ArrayList<GuiConfigFieldContribution>();
        var defaults = new TranslationConfig();
        requests.add(field("translation.ai-segment-size", GuiConfigFieldType.INT,
                Integer.toString(defaults.getAiSegmentSize()), 150, List.of(),
                GuiConfigCondition.equalsTo("translation.engine", "ai")));
        for (String engine : List.of("youdao", "libretranslate")) {
            var settings = defaults.machine(engine);
            requests.add(requestField(engine, "request-chars", settings.getRequestChars(), 1,
                    engine.equals("youdao") ? 5000 : 1_000_000, 200));
            requests.add(requestField(engine, "batch-size", settings.getBatchSize(), 1, 100, 210));
            requests.add(requestField(engine, "request-interval-ms", settings.getRequestIntervalMs(), 0, 60_000, 220));
            requests.add(requestField(engine, "request-concurrency", settings.getRequestConcurrency(), 1, 32, 230));
            requests.add(requestField(engine, "max-retries", settings.getMaxRetries(), 0, 10, 240));
            requests.add(requestField(engine, "read-timeout-seconds", settings.getReadTimeoutSeconds(), 1, 3600, 250));
            requests.add(field("translation." + engine + ".use-proxy", GuiConfigFieldType.BOOL,
                    Boolean.toString(settings.isUseProxy()), 260, List.of(), GuiConfigCondition.equalsTo("translation.engine", engine)));
        }
        var automatic = List.of(field("translation.max-concurrent", GuiConfigFieldType.INT,
                Integer.toString(defaults.getMaxConcurrent()), 300, List.of()));
        return List.of(new GuiConfigContribution(
                List.of(new GuiConfigGroupContribution(ID, "plugin.name", "translate", 750, true)),
                Stream.of(connection, requests, automatic).flatMap(List::stream).toList(),
                List.of(
                        section("service", "", 100, connection),
                        section("requests", "gui.config.section.translation.requests.title", 200, requests),
                        section("automatic", "gui.config.section.translation.automatic.title", 300, automatic)
                ),
                java.util.Set.of(),
                List.of(new GuiConfigOptionRequirement(
                        "translation.engine",
                        "ai",
                        "ai.chat.configuration",
                        "translation.engine.ai-unavailable"
                ))
        ));
    }

    private static String labelKey(String key) {
        return "gui.config.field." + key.replace("translation.youdao.", "translation.request.")
                .replace("translation.libretranslate.", "translation.request.") + ".label";
    }

    private static String helpKey(String key) {
        if (key.endsWith(".request-chars")) return "gui.config.field." + key + ".help";
        return labelKey(key).replace(".label", ".help");
    }

    private static GuiConfigFieldContribution requestField(String engine, String name, int value, int min, int max, int order) {
        String key = "translation." + engine + "." + name;
        return new GuiConfigFieldContribution(key, ID, labelKey(key), helpKey(key), "translate",
                GuiConfigFieldType.INT, Integer.toString(value), order, false, GuiConfigEffect.BACKEND_RESTART,
                List.of(), List.of(), List.of(GuiConfigCondition.equalsTo("translation.engine", engine)), min, max, true, Map.of());
    }

    private static GuiConfigSectionContribution section(
            String name,
            String titleKey,
            int order,
            List<GuiConfigFieldContribution> fields
    ) {
        return new GuiConfigSectionContribution(
                ID + "." + name,
                ID,
                titleKey,
                "gui.config.section.translation." + name + ".help",
                "translate",
                GuiConfigSectionLayout.FIELD_LIST,
                order,
                fields.stream().map(field -> new GuiConfigFieldLayoutContribution(field.key(), field.order())).toList(),
                List.of(),
                List.of()
        );
    }

    private static GuiConfigFieldContribution field(
            String key,
            GuiConfigFieldType type,
            String value,
            int order,
            List<String> options,
            GuiConfigCondition... visibleWhen
    ) {
        return new GuiConfigFieldContribution(
                key,
                ID,
                labelKey(key),
                helpKey(key),
                "translate",
                type,
                value,
                order,
                false,
                GuiConfigEffect.BACKEND_RESTART,
                options,
                List.of(),
                List.of(visibleWhen),
                type == GuiConfigFieldType.INT ? (key.equals("translation.ai-segment-size") ? 0 : 1) : null,
                key.equals("translation.ai-segment-size") ? 1_000_000 : null,
                true,
                type == GuiConfigFieldType.ENUM ? Map.of("ai", "translation.engine.ai", "youdao", "translation.engine.youdao",
                        "libretranslate", "translation.engine.libretranslate") : Map.of()
        );
    }
}
