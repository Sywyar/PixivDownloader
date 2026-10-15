package top.sywyar.pixivdownload.novel.translation.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /** 解析模型回复的 JSON 文本；解析失败时返回 {@code valid=false} 的空响应。 */
    public static LangProbeResponse parse(String content) {
        if (content == null || content.isBlank()) {
            return new LangProbeResponse("", false);
        }
        try {
            return TranslationResponse.parse(content.trim(), LangProbeResponse.class);
        } catch (IllegalArgumentException ignored) {
            return new LangProbeResponse("", false);
        }
    }

    /** 是否得到了可用的语言代码。 */
    public boolean ok() {
        return valid && code != null && !code.isBlank();
    }
}
