package top.sywyar.pixivdownload.tts;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;
import java.util.Map;

public class TtsPlugin implements PixivFeaturePlugin {

    public static final String ID = "tts";
    public static final String GUI_SECTION_ID = "ai.modalities";
    private static final String TTS_CARD_ID = "tts";
    private static final String ENGINE_KEY = "narration-tts.engine";

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
        return "audio-lines";
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
        return List.of(
                WebRouteContribution.visitorAndInvitedGuest("/api/tts/**"),
                WebRouteContribution.visitorAndInvitedGuest("/pixiv-tts/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(ID, "classpath:/static/pixiv-tts/", "/pixiv-tts/"));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return List.of(new WebUiSlotContribution(
                ID, ID + ".novel-detail-tts", "novel-detail-tts",
                "/pixiv-tts/novel-detail-tts-slot.js", 10));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.tts", 11));
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        List<GuiConfigFieldContribution> fields = List.of(
                engineEnumeration("voxcpm", 100,
                        List.of("voxcpm", "mimo", "cosyvoice", "fish", "minimax", "elevenlabs", "qwen", "doubao")),

                string("narration-tts.voxcpm.base-url", "", 110, engine("voxcpm")),
                password("narration-tts.voxcpm.api-key", "", 111, engine("voxcpm")),
                string("narration-tts.voxcpm.model", "openbmb/VoxCPM2", 112, engine("voxcpm")),
                string("narration-tts.voxcpm.voice", "", 113, engine("voxcpm")),
                enumeration("narration-tts.voxcpm.response-format", "wav", 114, List.of("wav", "pcm"), engine("voxcpm")),
                bool("narration-tts.voxcpm.use-proxy", "false", 115, engine("voxcpm")),
                bool("narration-tts.voxcpm.enable-clone", "true", 116, engine("voxcpm")),
                enumeration("narration-tts.voxcpm.clone-mode", "controllable", 117,
                        List.of("controllable", "hifi"),
                        engine("voxcpm"), GuiConfigCondition.isTrue("narration-tts.voxcpm.enable-clone")),
                integerMin("narration-tts.voxcpm.max-new-tokens", "4096", 118, 0, engine("voxcpm")),

                string("narration-tts.mimo.base-url", "https://api.xiaomimimo.com/v1", 130, engine("mimo")),
                password("narration-tts.mimo.api-key", "", 131, engine("mimo")),
                string("narration-tts.mimo.model", "mimo-v2.5-tts", 132, engine("mimo")),
                string("narration-tts.mimo.voice-design-model", "mimo-v2.5-tts-voicedesign", 133, engine("mimo")),
                string("narration-tts.mimo.voice-clone-model", "mimo-v2.5-tts-voiceclone", 134, engine("mimo")),
                string("narration-tts.mimo.voice", "", 135, engine("mimo")),
                enumeration("narration-tts.mimo.response-format", "wav", 136, List.of("wav", "pcm16"), engine("mimo")),
                bool("narration-tts.mimo.use-proxy", "false", 137, engine("mimo")),
                bool("narration-tts.mimo.enable-clone", "true", 138, engine("mimo")),

                string("narration-tts.cosyvoice.base-url", "", 150, engine("cosyvoice")),
                password("narration-tts.cosyvoice.api-key", "", 151, engine("cosyvoice")),
                string("narration-tts.cosyvoice.model", "CosyVoice2-0.5B", 152, engine("cosyvoice")),
                string("narration-tts.cosyvoice.voice", "", 153, engine("cosyvoice")),
                enumeration("narration-tts.cosyvoice.response-format", "wav", 154,
                        List.of("wav", "mp3", "pcm"), engine("cosyvoice")),
                bool("narration-tts.cosyvoice.use-proxy", "false", 155, engine("cosyvoice")),
                bool("narration-tts.cosyvoice.enable-clone", "true", 156, engine("cosyvoice")),

                string("narration-tts.fish.base-url", "https://api.fish.audio", 170, engine("fish")),
                password("narration-tts.fish.api-key", "", 171, engine("fish")),
                string("narration-tts.fish.model", "s1", 172, engine("fish")),
                string("narration-tts.fish.reference-id", "", 173, engine("fish")),
                enumeration("narration-tts.fish.format", "mp3", 174, List.of("mp3", "wav", "pcm", "opus"), engine("fish")),
                bool("narration-tts.fish.use-proxy", "false", 175, engine("fish")),

                string("narration-tts.minimax.base-url", "https://api.minimax.io/v1", 190, engine("minimax")),
                password("narration-tts.minimax.api-key", "", 191, engine("minimax")),
                string("narration-tts.minimax.group-id", "", 192, engine("minimax")),
                string("narration-tts.minimax.model", "speech-2.8-hd", 193, engine("minimax")),
                string("narration-tts.minimax.voice-id", "", 194, engine("minimax")),
                string("narration-tts.minimax.emotion", "", 195, engine("minimax")),
                enumeration("narration-tts.minimax.format", "mp3", 196,
                        List.of("mp3", "wav", "pcm", "flac"), engine("minimax")),
                integerMin("narration-tts.minimax.sample-rate", "32000", 197, 1, engine("minimax")),
                bool("narration-tts.minimax.use-proxy", "false", 198, engine("minimax")),

                string("narration-tts.elevenlabs.base-url", "https://api.elevenlabs.io", 210, engine("elevenlabs")),
                password("narration-tts.elevenlabs.api-key", "", 211, engine("elevenlabs")),
                string("narration-tts.elevenlabs.model", "eleven_v3", 212, engine("elevenlabs")),
                string("narration-tts.elevenlabs.voice-id", "", 213, engine("elevenlabs")),
                enumeration("narration-tts.elevenlabs.output-format", "mp3_44100_128", 214,
                        List.of("mp3_44100_128", "mp3_44100_64", "pcm_16000",
                                "pcm_24000", "opus_48000_128", "ulaw_8000"),
                        engine("elevenlabs")),
                bool("narration-tts.elevenlabs.use-proxy", "false", 215, engine("elevenlabs")),

                string("narration-tts.qwen.base-url", "https://dashscope.aliyuncs.com/api/v1", 230, engine("qwen")),
                password("narration-tts.qwen.api-key", "", 231, engine("qwen")),
                string("narration-tts.qwen.model", "qwen3-tts-flash", 232, engine("qwen")),
                string("narration-tts.qwen.voice", "Cherry", 233, engine("qwen")),
                string("narration-tts.qwen.language-type", "", 234, engine("qwen")),
                bool("narration-tts.qwen.use-proxy", "false", 235, engine("qwen")),

                string("narration-tts.doubao.base-url", "https://openspeech.bytedance.com", 250, engine("doubao")),
                string("narration-tts.doubao.app-id", "", 251, engine("doubao")),
                password("narration-tts.doubao.access-token", "", 252, engine("doubao")),
                string("narration-tts.doubao.cluster", "volcano_tts", 253, engine("doubao")),
                string("narration-tts.doubao.voice-type", "", 254, engine("doubao")),
                enumeration("narration-tts.doubao.encoding", "mp3", 255,
                        List.of("mp3", "wav", "pcm", "ogg_opus"), engine("doubao")),
                string("narration-tts.doubao.emotion", "", 256, engine("doubao")),
                bool("narration-tts.doubao.use-proxy", "false", 257, engine("doubao")));
        return List.of(new GuiConfigContribution(
                List.of(),
                fields,
                List.of(ttsSection(fields))));
    }

    private static GuiConfigSectionContribution ttsSection(List<GuiConfigFieldContribution> fields) {
        return new GuiConfigSectionContribution(
                GUI_SECTION_ID,
                GuiConfigGroups.AI,
                "",
                "",
                ID,
                "gui.config.tts.modality.label",
                "gui.config.tts.modality.help",
                "",
                "",
                List.of(),
                GuiConfigSectionLayout.CARD_SWITCHER,
                1200,
                fields.stream()
                        .map(field -> new GuiConfigFieldLayoutContribution(
                                field.key(), TTS_CARD_ID, "gui.config.tts.modality.tts", ID, field.order()))
                        .toList(),
                List.of(),
                List.of(),
                true,
                true);
    }

    private static GuiConfigCondition engine(String id) {
        return GuiConfigCondition.equalsTo(ENGINE_KEY, id);
    }

    private static GuiConfigFieldContribution engineEnumeration(String defaultValue, int order,
                                                                List<String> enumValues) {
        return field(ENGINE_KEY, GuiConfigFieldType.ENUM, defaultValue, order, enumValues,
                null, null, engineValueLabelKeys(enumValues));
    }

    private static GuiConfigFieldContribution bool(String key, String defaultValue, int order,
                                                   GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.BOOL, defaultValue, order, List.of(), null, null, enabledWhen);
    }

    private static GuiConfigFieldContribution string(String key, String defaultValue, int order,
                                                     GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.STRING, defaultValue, order, List.of(), null, null, enabledWhen);
    }

    private static GuiConfigFieldContribution password(String key, String defaultValue, int order,
                                                       GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.PASSWORD, defaultValue, order, List.of(), null, null, enabledWhen);
    }

    private static GuiConfigFieldContribution integerMin(String key, String defaultValue, int order,
                                                         int minValue,
                                                         GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.INT, defaultValue, order, List.of(), minValue, null, enabledWhen);
    }

    private static GuiConfigFieldContribution enumeration(String key, String defaultValue, int order,
                                                          List<String> enumValues,
                                                          GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.ENUM, defaultValue, order, enumValues, null, null, enabledWhen);
    }

    private static GuiConfigFieldContribution field(String key, GuiConfigFieldType type, String defaultValue,
                                                    int order, List<String> enumValues, Integer minValue,
                                                    Integer maxValue, GuiConfigCondition... enabledWhen) {
        return field(key, type, defaultValue, order, enumValues, minValue, maxValue, Map.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution field(String key, GuiConfigFieldType type, String defaultValue,
                                                    int order, List<String> enumValues, Integer minValue,
                                                    Integer maxValue, Map<String, String> enumValueLabelKeys,
                                                    GuiConfigCondition... enabledWhen) {
        String prefix = "gui.config.field." + key;
        List<GuiConfigCondition> enabledConditions = List.of(enabledWhen);
        return new GuiConfigFieldContribution(key, GuiConfigGroups.NARRATION_TTS, prefix + ".label", prefix + ".help",
                ID, type, defaultValue, order, false, false, enumValues,
                enabledConditions, visibleWhenEngine(enabledConditions), minValue, maxValue,
                true, enumValueLabelKeys);
    }

    private static List<GuiConfigCondition> visibleWhenEngine(List<GuiConfigCondition> enabledConditions) {
        return enabledConditions.stream()
                .filter(condition -> ENGINE_KEY.equals(condition.key()))
                .toList();
    }

    private static Map<String, String> engineValueLabelKeys(List<String> enumValues) {
        java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>();
        for (String value : enumValues) {
            labels.put(value, "gui.config.field.narration-tts.engine.value." + value);
        }
        return Map.copyOf(labels);
    }
}
