package top.sywyar.pixivdownload.ai.translation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 发给 AI 的一条「名词映射」术语：{@code source}（原文专有名词）在目标语言 {@code lang}（BCP-47）下应译为
 * {@code target}。{@link TranslationRequest} 把映射表条目以该形态注入提示词，要求模型对<b>语言匹配</b>的
 * 条目强制使用既定译名，从而保证跨段 / 跨章的术语一致。
 */
public record GlossaryTerm(
        @JsonProperty("source") String source,
        @JsonProperty("lang") String lang,
        @JsonProperty("target") String target
) {
}
