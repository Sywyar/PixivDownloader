package top.sywyar.pixivdownload.novel.translation.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 「Pixiv 小说 / 系列标题翻译」的 AI 响应体，对应 {@link TitleTranslationRequest} 约定的 JSON 输出规范。
 *
 * <p>{@link #parse(String)} 把模型回复的 JSON 文本解析为本结构；为兼容个别模型在 JSON 外包裹少量文字
 * （如 ```json 代码块）的情况，解析失败时会回退到截取首个 {@code '{'} 到末个 {@code '}'} 的子串再试一次。
 *
 * @param status 状态码：{@link #STATUS_OK} 表示翻译成功；{@link #STATUS_INVALID_LANGUAGE} 表示目标语言不存在
 * @param lang   实际翻译语言的通用代码（BCP-47，如 {@code zh-CN} / {@code en-US}）
 * @param title  译后标题（{@code invalid_language} 时为空）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TitleTranslationResponse(
        @JsonProperty("status") String status,
        @JsonProperty("lang") String lang,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description
) {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_INVALID_LANGUAGE = "invalid_language";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 翻译是否成功（状态码为 {@code ok} 且译文非空）。 */
    public boolean ok() {
        return STATUS_OK.equalsIgnoreCase(trimmed(status))
                && title != null && !title.isBlank();
    }

    /**
     * 模型本次回报的译后系列简介（同请求附带 sourceDescription 时才会有效）。
     * 缺省 / 空白 / 字面 {@code "null"} 一律视为「本次未译简介」，返回 {@code null}。
     */
    public String translatedDescription() {
        if (description == null) return null;
        String trimmed = description.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null")) return null;
        return trimmed;
    }

    /** 模型是否判定目标语言不存在 / 无法识别。 */
    public boolean invalidLanguage() {
        return STATUS_INVALID_LANGUAGE.equalsIgnoreCase(trimmed(status));
    }

    /** 解析模型回复的 JSON 文本；无法解析时抛 {@link IllegalArgumentException}。 */
    public static TitleTranslationResponse parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty AI response");
        }
        try {
            return MAPPER.readValue(content, TitleTranslationResponse.class);
        } catch (Exception first) {
            String extracted = extractJsonObject(content);
            if (extracted != null) {
                try {
                    return MAPPER.readValue(extracted, TitleTranslationResponse.class);
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
