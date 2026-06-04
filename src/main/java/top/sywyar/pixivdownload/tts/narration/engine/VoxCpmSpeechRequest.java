package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * VoxCPM OpenAI 兼容音频接口（{@code POST {base-url}/audio/speech}）的请求体线缆 DTO。
 * <p>
 * VoxCPM 没有独立的「control instruction」字段，音色描述用其 {@code (描述)正文} voice-design 语法拼进
 * {@link #input}（见 {@link VoxCpmNarrationEngine}）。
 * <p>
 * 可控克隆时另带 {@link #refAudio}（{@code data:audio/...;base64,} 形式，不依赖服务端开关）+ 可选
 * {@link #refText}（参考音转录，作 in-context 提升保真度），并设 {@link #maxNewTokens} 上限——已知
 * vllm-omni#2896：{@code ref_audio} 克隆停止符可能不触发，须有 token 上限兜底以免无限生成。克隆相关字段
 * 与 {@code maxNewTokens} 均 {@link JsonInclude.Include#NON_NULL}，无参考音时不出现，保持纯 voice-design 请求体不变。
 *
 * @param model          模型名
 * @param input          合成文本（已含内联 voice-design / delivery 前缀）
 * @param voice          voice id（固定 {@code default}）
 * @param responseFormat 音频输出格式（{@code wav} / {@code pcm}），线缆字段名 {@code response_format}
 * @param refAudio       参考音（{@code data:audio/...;base64,...}），线缆字段名 {@code ref_audio}；非克隆时为 {@code null}
 * @param refText        参考音转录文本，线缆字段名 {@code ref_text}；缺省 / 非克隆时为 {@code null}
 * @param maxNewTokens   生成 token 上限（克隆停止符兜底），线缆字段名 {@code max_new_tokens}；非克隆时为 {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoxCpmSpeechRequest(
        String model,
        String input,
        String voice,
        @JsonProperty("response_format") String responseFormat,
        @JsonProperty("ref_audio") String refAudio,
        @JsonProperty("ref_text") String refText,
        @JsonProperty("max_new_tokens") Integer maxNewTokens
) {

    /** 纯 voice-design（无克隆）请求体：克隆字段与 token 上限留空。 */
    public static VoxCpmSpeechRequest voiceDesign(String model, String input, String voice, String responseFormat) {
        return new VoxCpmSpeechRequest(model, input, voice, responseFormat, null, null, null);
    }
}
