package top.sywyar.pixivdownload.novel.translation.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /** 解析模型回复的 JSON 文本；解析失败时返回 {@code same=null} 的空响应。 */
    public static SourceLanguageProbeResponse parse(String content) {
        if (content == null || content.isBlank()) {
            return new SourceLanguageProbeResponse("", null);
        }
        try {
            return TranslationResponse.parse(content.trim(), SourceLanguageProbeResponse.class);
        } catch (IllegalArgumentException ignored) {
            return new SourceLanguageProbeResponse("", null);
        }
    }

    /** 模型是否明确判定「源语言与目标语言相同」。无法判定（{@code same=null}）时返回 {@code false}。 */
    public boolean isSame() {
        return Boolean.TRUE.equals(same);
    }
}
