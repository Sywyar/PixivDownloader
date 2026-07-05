package top.sywyar.pixivdownload.novel.translation.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 「语言名 → 规范 BCP-47 代码」的 AI 响应体，对应 {@link LangProbeRequest} 约定的 JSON 输出规范。
 *
 * <p>{@link #parse(String)} 把模型回复的 JSON 文本解析为本结构；解析失败或字段缺失时返回
 * {@code valid=false}。
 *
 * @param code  规范 BCP-47 代码（{@code valid=false} 时为空字符串）
 * @param valid 输入是否被判定为真实存在的人类语言
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LangProbeResponse(
        @JsonProperty("code") String code,
        @JsonProperty("valid") boolean valid
) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 解析模型回复的 JSON 文本；解析失败时返回 {@code valid=false} 的空响应。 */
    public static LangProbeResponse parse(String content) {
        if (content == null || content.isBlank()) {
            return new LangProbeResponse("", false);
        }
        String trimmed = content.trim();
        try {
            return MAPPER.readValue(trimmed, LangProbeResponse.class);
        } catch (Exception ignored) {
        }
        // 兼容个别模型在 JSON 外包裹少量文字（如 ```json 代码块）的情况
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return MAPPER.readValue(trimmed.substring(start, end + 1), LangProbeResponse.class);
            } catch (Exception ignored) {
            }
        }
        return new LangProbeResponse("", false);
    }

    /** 是否得到了可用的语言代码。 */
    public boolean ok() {
        return valid && code != null && !code.isBlank();
    }
}
