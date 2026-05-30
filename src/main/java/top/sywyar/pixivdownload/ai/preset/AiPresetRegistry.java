package top.sywyar.pixivdownload.ai.preset;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AI 服务商预设的唯一事实源。
 * <p>
 * 列表顺序即 GUI 下拉框顺序：海外厂商 → 国内厂商 → 本地 / 自托管 → 聚合平台 → 自定义哨兵。
 * 所有预设都以 <b>OpenAI Chat Completions 兼容协议</b> 接入（Claude / Gemini 用各自的 OpenAI 兼容端点）。
 * <p>
 * GUI 选中某预设时锁定 base-url（并以预设的默认模型 / 代理开关回填，但不锁定模型与代理开关）；选中
 * {@link AiPreset#CUSTOM_ID} 解锁。预设本身不进 {@code config.yaml}（配置语义仍是 base-url / model），
 * 加载时按已存 base-url 反查推断。
 */
@Component
public class AiPresetRegistry {

    private final List<AiPreset> presets;
    private final Map<String, AiPreset> byId;

    public AiPresetRegistry() {
        this.presets = List.of(
                // ── 海外（墙内一般需代理）────────────────────────────────────────
                preset("openai", "https://api.openai.com/v1", "gpt-5.4-mini", true,
                        "ai.preset.help.api-key"),
                preset("anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5", true,
                        "ai.preset.help.anthropic"),
                preset("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true,
                        "ai.preset.help.gemini"),
                preset("xai", "https://api.x.ai/v1", "grok-4", true,
                        "ai.preset.help.api-key"),
                preset("mistral", "https://api.mistral.ai/v1", "mistral-large-latest", true,
                        "ai.preset.help.api-key"),
                preset("groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true,
                        "ai.preset.help.api-key"),

                // ── 国内（一般无需代理）──────────────────────────────────────────
                preset("deepseek", "https://api.deepseek.com", "deepseek-v4-flash", false,
                        "ai.preset.help.api-key"),
                preset("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", false,
                        "ai.preset.help.dashscope"),
                preset("zhipu", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash", false,
                        "ai.preset.help.api-key"),
                preset("moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k", false,
                        "ai.preset.help.api-key"),
                preset("doubao", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-250615", false,
                        "ai.preset.help.doubao"),
                preset("hunyuan", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest", false,
                        "ai.preset.help.api-key"),
                preset("ernie", "https://qianfan.baidubce.com/v2", "ernie-4.0-turbo-8k", false,
                        "ai.preset.help.ernie"),
                preset("spark", "https://spark-api-open.xf-yun.com/v1", "generalv3.5", false,
                        "ai.preset.help.spark"),
                preset("minimax", "https://api.minimaxi.com/v1", "MiniMax-M2", false,
                        "ai.preset.help.minimax"),

                // ── 本地 / 自托管（无需代理；自定义模型如 MiMo 等开源模型走这里）────
                preset("ollama", "http://localhost:11434/v1", "llama3.1", false,
                        "ai.preset.help.local"),
                preset("lmstudio", "http://localhost:1234/v1", "", false,
                        "ai.preset.help.local"),

                // ── 聚合平台 ──────────────────────────────────────────────────────
                preset("openrouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", true,
                        "ai.preset.help.aggregator"),
                preset("siliconflow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", false,
                        "ai.preset.help.aggregator"),

                // ── 自定义哨兵（必须位于末尾）─────────────────────────────────────
                new AiPreset(AiPreset.CUSTOM_ID, "ai.preset.name." + AiPreset.CUSTOM_ID,
                        "", "", false, "ai.preset.help.custom")
        );

        Map<String, AiPreset> map = new LinkedHashMap<>();
        for (AiPreset preset : presets) {
            if (map.put(preset.id(), preset) != null) {
                throw new IllegalStateException("duplicate ai preset id: " + preset.id());
            }
        }
        this.byId = Map.copyOf(map);
    }

    /** 全部预设（包含末尾 {@code custom} 哨兵），按 GUI 下拉顺序。 */
    public List<AiPreset> all() {
        return presets;
    }

    public Optional<AiPreset> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 按 base-url 反查预设（忽略大小写与结尾斜杠）。
     * <p>
     * GUI 加载已有 config.yaml 时用此方法推断当前是哪一个预设；未命中时 GUI 应落到 {@code custom}。
     */
    public Optional<AiPreset> findByBaseUrl(String baseUrl) {
        String normalized = normalize(baseUrl);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return presets.stream()
                .filter(p -> !p.isCustom())
                .filter(p -> normalize(p.baseUrl()).equals(normalized))
                .findFirst();
    }

    /** {@code custom} 哨兵；列表末尾。 */
    public AiPreset custom() {
        return byId.get(AiPreset.CUSTOM_ID);
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim().toLowerCase(Locale.ROOT);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static AiPreset preset(String id, String baseUrl, String defaultModel,
                                   boolean defaultUseProxy, String credentialHelpKey) {
        return new AiPreset(id, "ai.preset.name." + id, baseUrl, defaultModel,
                defaultUseProxy, credentialHelpKey);
    }
}
