package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * VoxCPM OpenAI 兼容音频接口（{@code POST {base-url}/audio/speech}）的请求体线缆 DTO。
 * <p>
 * VoxCPM 没有独立的「control instruction」字段，音色描述用其 {@code (描述)正文} voice-design 语法拼进
 * {@link #input}（见 {@link VoxCpmNarrationEngine}）。
 *
 * @param input          合成文本（已含内联 voice-design 前缀）
 * @param model          模型名
 * @param voice          voice id（内联 voice-design 用固定 {@code default}）
 * @param responseFormat 音频输出格式（{@code wav} / {@code pcm}），线缆字段名 {@code response_format}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoxCpmSpeechRequest(
        String model,
        String input,
        String voice,
        @JsonProperty("response_format") String responseFormat
) {
}
