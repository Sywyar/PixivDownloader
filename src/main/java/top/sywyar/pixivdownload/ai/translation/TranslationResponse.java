package top.sywyar.pixivdownload.ai.translation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Pixiv 小说文本翻译」的 AI 响应体，对应 {@link TranslationRequest} 约定的 JSON 输出规范。
 *
 * <p>{@link #parse(String)} 把模型回复的 JSON 文本解析为本结构；为兼容个别模型在 JSON 外包裹少量文字
 * （如 ```json 代码块）的情况，解析失败时会回退到截取首个 {@code '{'} 到末个 {@code '}'} 的子串再试一次。
 *
 * @param status   状态码：{@link #STATUS_OK} 表示翻译成功；{@link #STATUS_INVALID_LANGUAGE} 表示目标语言不存在
 * @param lang     实际翻译语言的通用代码（BCP-47，如 {@code zh-CN} / {@code en-US}）
 * @param text     译文文本（{@code invalid_language} 时为空）
 * @param glossary 模型在本次翻译中遇到、且映射表尚无的新专有名词（{@code source → target}），供自动入库复用
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranslationResponse(
        @JsonProperty("status") String status,
        @JsonProperty("lang") String lang,
        @JsonProperty("text") String text,
        @JsonProperty("glossary") List<NewTerm> glossary
) {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_INVALID_LANGUAGE = "invalid_language";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 模型回报的一条新名词映射（原文 → 译名）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewTerm(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target
    ) {
    }

    /** 翻译是否成功（状态码为 {@code ok} 且译文非 null）。 */
    public boolean ok() {
        return STATUS_OK.equalsIgnoreCase(trimmed(status)) && text != null;
    }

    /** 模型是否判定目标语言不存在 / 无法识别。 */
    public boolean invalidLanguage() {
        return STATUS_INVALID_LANGUAGE.equalsIgnoreCase(trimmed(status));
    }

    /** 模型回报的新名词（过滤掉 source / target 为空者）；从不返回 {@code null}。 */
    public List<NewTerm> newTerms() {
        if (glossary == null || glossary.isEmpty()) {
            return List.of();
        }
        List<NewTerm> out = new ArrayList<>(glossary.size());
        for (NewTerm t : glossary) {
            if (t == null) continue;
            if (t.source() == null || t.source().isBlank()) continue;
            if (t.target() == null || t.target().isBlank()) continue;
            out.add(t);
        }
        return out;
    }

    /** 解析模型回复的 JSON 文本；无法解析时抛 {@link IllegalArgumentException}。 */
    public static TranslationResponse parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty AI response");
        }
        try {
            return MAPPER.readValue(content, TranslationResponse.class);
        } catch (Exception first) {
            String extracted = extractJsonObject(content);
            if (extracted != null) {
                try {
                    return MAPPER.readValue(extracted, TranslationResponse.class);
                } catch (Exception ignored) {
                    // fall through to throwing the original failure
                }
            }
            throw new IllegalArgumentException("unparseable AI response: " + first.getMessage(), first);
        }
    }

    /** 截取首个 '{' 到末个 '}' 的子串，容忍模型在 JSON 外包裹少量说明文字 / 代码围栏。 */
    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }
}
