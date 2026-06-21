package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Fish Audio TTS（{@code POST {base-url}/v1/tts}）的请求体线缆 DTO。音色由 {@link #referenceId}（Fish 控制台预创建的
 * 声音模型 id）决定；逐句情绪以行内 {@code (delivery)} 标记注入 {@link #text}（Fish 支持文本内自然语言情绪标记）。
 * 模型经 {@code model} 请求头指定（不在请求体里）。响应为二进制音频。
 *
 * @param text        待合成文本（可含行内 {@code (情绪)} 标记）
 * @param referenceId 声音模型 id（{@code reference_id}）；可空则用账户默认音色
 * @param format      输出格式（{@code mp3} / {@code wav} / {@code pcm} / {@code opus}）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FishTtsRequest(
        String text,
        @JsonProperty("reference_id") String referenceId,
        String format
) {
}
