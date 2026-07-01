package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ElevenLabs Text-to-Speech（{@code POST {base-url}/v1/text-to-speech/{voice_id}}）的请求体线缆 DTO。音色由 URL 路径里的
 * {@code voice_id}（ElevenLabs 控制台预建 / 克隆的声音）决定；逐句情绪以 Eleven v3 的行内<b>音频标签</b>（方括号自然语言
 * 指令，如 {@code [whispers]} / {@code [angry]}）注入 {@link #text}。模型经 {@link #modelId} 指定，输出格式经 query 参数
 * {@code output_format}（不在请求体里）。响应为二进制音频（默认 mp3）。
 *
 * @param text    待合成文本（可含行内 {@code [情绪]} 音频标签，仅 Eleven v3 解析）
 * @param modelId 模型 id（{@code model_id}，如 {@code eleven_v3} / {@code eleven_multilingual_v2}）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ElevenLabsTtsRequest(
        String text,
        @JsonProperty("model_id") String modelId
) {
}
