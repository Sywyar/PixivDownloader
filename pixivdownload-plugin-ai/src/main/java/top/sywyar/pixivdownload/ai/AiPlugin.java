package top.sywyar.pixivdownload.ai;

import top.sywyar.pixivdownload.ai.preset.AiPreset;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultOperator;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultRule;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSource;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetMatchMode;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AiPlugin implements PixivFeaturePlugin {

    public static final String ID = "ai";
    public static final String GUI_SECTION_ID = "ai.modalities";
    private static final String TEXT_CARD_ID = "text";

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
        return "sparkles";
    }

    @Override
    public String colorToken() {
        return "teal";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.gui("/api/gui/ai-test"),
                WebRouteContribution.admin("/api/admin/ai/**"),
                WebRouteContribution.visitorAndInvitedGuest("/pixiv-ai/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution("classpath:/static/pixiv-ai/", "/pixiv-ai/"));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return List.of(
                new WebUiSlotContribution(ID + ".novel-detail-translate", "novel-detail-ai-translate",
                        "/pixiv-ai/novel-detail-ai-translate-slot.js", 20),
                new WebUiSlotContribution(ID + ".series-detail-translate", "series-detail-ai-translate",
                        "/pixiv-ai/series-detail-ai-translate-slot.js", 20),
                new WebUiSlotContribution(ID + ".download-novel-translate-settings",
                        "settings-card", "/pixiv-ai/download-novel-ai-settings-slot.js", 20));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(
                new I18nContribution(ID, "i18n.web.ai", 10),
                new I18nContribution("translate", "i18n.web.translate", 13));
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        List<GuiConfigFieldContribution> fields = List.of(
                bool("ai.enabled", "false", 100),
                string("ai.base-url", "", 110, GuiConfigCondition.isTrue("ai.enabled")),
                password("ai.api-key", "", 120, GuiConfigCondition.isTrue("ai.enabled")),
                string("ai.model", "", 130, GuiConfigCondition.isTrue("ai.enabled")),
                bool("ai.use-proxy", "false", 140, GuiConfigCondition.isTrue("ai.enabled")));
        return List.of(new GuiConfigContribution(
                List.of(),
                fields,
                List.of(aiSection(fields))));
    }

    private static GuiConfigSectionContribution aiSection(List<GuiConfigFieldContribution> fields) {
        return new GuiConfigSectionContribution(
                GUI_SECTION_ID,
                GuiConfigGroups.AI,
                "",
                "",
                ID,
                "gui.config.ai.modality.label",
                "gui.config.ai.modality.help",
                "gui.config.ai.preset.label",
                "gui.config.ai.preset.help",
                List.of(),
                GuiConfigSectionLayout.CARD_SWITCHER,
                1200,
                fields.stream()
                        .map(field -> new GuiConfigFieldLayoutContribution(
                                field.key(), TEXT_CARD_ID, "gui.config.ai.modality.text", ID, field.order()))
                        .toList(),
                List.of(aiTestAction()),
                aiPresets(),
                true,
                true);
    }

    private static GuiConfigActionContribution aiTestAction() {
        return new GuiConfigActionContribution(
                "ai.test",
                "gui.config.ai.test-button.label",
                "gui.config.ai.test-button.help",
                ID,
                TEXT_CARD_ID,
                "ai-test",
                120_000,
                1000,
                List.of(
                        new GuiConfigActionPayloadField("baseUrl", "ai.base-url"),
                        new GuiConfigActionPayloadField("apiKey", "ai.api-key"),
                        new GuiConfigActionPayloadField("model", "ai.model"),
                        new GuiConfigActionPayloadField(
                                "useProxy", "ai.use-proxy", GuiConfigActionPayloadType.BOOLEAN)),
                "gui.config.ai.test.notice.sending",
                List.of(
                        new GuiConfigActionResultRule(
                                "gui.config.ai.test.notice.unreachable",
                                ID,
                                10,
                                List.of(GuiConfigActionResultCondition.reachable(false)),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.ai.test.notice.success",
                                ID,
                                20,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonTrue("success")),
                                List.of(GuiConfigActionResultArgument.json("reply"))),
                        new GuiConfigActionResultRule(
                                "gui.config.ai.test.notice.failed",
                                ID,
                                30,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonFalse("success")),
                                List.of(GuiConfigActionResultArgument.json("error"))),
                        new GuiConfigActionResultRule(
                                "gui.config.ai.test.notice.failed",
                                ID,
                                40,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(false),
                                        rawBodyCondition(GuiConfigActionResultOperator.NOT_BLANK)),
                                List.of(rawBodyArgument())),
                        new GuiConfigActionResultRule(
                                "gui.config.ai.test.notice.failed",
                                ID,
                                50,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(false),
                                        rawBodyCondition(GuiConfigActionResultOperator.BLANK)),
                                List.of(new GuiConfigActionResultArgument(
                                        GuiConfigActionResultSource.HTTP_STATUS_TEXT, "", "")))),
                null);
    }

    private static List<GuiConfigPresetContribution> aiPresets() {
        List<AiPreset> presets = new AiPresetRegistry().all();
        java.util.ArrayList<GuiConfigPresetContribution> contributions = new java.util.ArrayList<>();
        for (int i = 0; i < presets.size(); i++) {
            AiPreset preset = presets.get(i);
            contributions.add(aiPreset(preset, i * 10));
        }
        return List.copyOf(contributions);
    }

    private static GuiConfigPresetContribution aiPreset(AiPreset preset, int order) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!preset.isCustom()) {
            values.put("ai.base-url", preset.baseUrl());
            values.put("ai.model", preset.defaultModel());
            values.put("ai.use-proxy", Boolean.toString(preset.defaultUseProxy()));
        }
        return new GuiConfigPresetContribution(
                preset.id(),
                preset.displayNameKey(),
                preset.credentialHelpKey(),
                ID,
                TEXT_CARD_ID,
                order,
                preset.isCustom() ? null : "ai.base-url",
                preset.isCustom() ? "" : preset.baseUrl(),
                values,
                preset.isCustom() ? List.of() : List.of("ai.base-url"),
                GuiConfigPresetMatchMode.TRIMMED_TRAILING_SLASH_IGNORE_CASE);
    }

    private static GuiConfigActionResultCondition rawBodyCondition(GuiConfigActionResultOperator operator) {
        return new GuiConfigActionResultCondition(GuiConfigActionResultSource.RAW_BODY, "", operator, "");
    }

    private static GuiConfigActionResultArgument rawBodyArgument() {
        return new GuiConfigActionResultArgument(GuiConfigActionResultSource.RAW_BODY, "", "");
    }

    private static GuiConfigFieldContribution bool(String key, String defaultValue, int order,
                                                   GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.BOOL, defaultValue, order, List.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution string(String key, String defaultValue, int order,
                                                     GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.STRING, defaultValue, order, List.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution password(String key, String defaultValue, int order,
                                                       GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.PASSWORD, defaultValue, order, List.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution field(String key, GuiConfigFieldType type, String defaultValue,
                                                    int order, List<String> enumValues,
                                                    GuiConfigCondition... enabledWhen) {
        String prefix = "gui.config.field." + key;
        return new GuiConfigFieldContribution(key, GuiConfigGroups.AI, prefix + ".label", prefix + ".help",
                ID, type, defaultValue, order, false, false, enumValues,
                List.of(enabledWhen), List.of(), null, null);
    }
}
