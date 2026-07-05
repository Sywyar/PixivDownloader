package top.sywyar.pixivdownload.novel.translation.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 「源语言是否等于目标语言」的 AI 响应体，对应 {@link SourceLanguageProbeRequest} 约定的 JSON 输出规范。
 *
 * <p>{@link #parse(String)} 把模型回复的 JSON 文本解析为本结构；解析失败或字段缺失时返回
 * {@code same=null}，由调用方按「无法判定 → 照常翻译」处理（best-effort，绝不因探测失败而漏译）。
 *
 * @param sourceLang 样本文本被判定的 BCP-47 语言代码（可能为空）
 * @param same       样本语言是否与目标语言相同；{@code null} 表示模型未给出 / 无法判定
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SourceLanguageProbeResponse(
        @JsonProperty("sourceLang") String sourceLang,
        @JsonProperty("same") Boolean same
) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 解析模型回复的 JSON 文本；解析失败时返回 {@code same=null} 的空响应。 */
    public static SourceLanguageProbeResponse parse(String content) {
        if (content == null || content.isBlank()) {
            return new SourceLanguageProbeResponse("", null);
        }
        String trimmed = content.trim();
        try {
            return MAPPER.readValue(trimmed, SourceLanguageProbeResponse.class);
        } catch (Exception ignored) {
        }
        // 兼容个别模型在 JSON 外包裹少量文字（如 ```json 代码块）的情况
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return MAPPER.readValue(trimmed.substring(start, end + 1), SourceLanguageProbeResponse.class);
            } catch (Exception ignored) {
            }
        }
        return new SourceLanguageProbeResponse("", null);
    }

    /** 模型是否明确判定「源语言与目标语言相同」。无法判定（{@code same=null}）时返回 {@code false}。 */
    public boolean isSame() {
        return Boolean.TRUE.equals(same);
    }
}
