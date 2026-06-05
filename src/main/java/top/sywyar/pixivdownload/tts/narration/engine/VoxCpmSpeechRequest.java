package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * VoxCPM OpenAI 兼容音频接口（{@code POST {base-url}/audio/speech}）的请求体线缆 DTO。
 * <p>
 * VoxCPM 没有独立的「control instruction」字段，音色 / 风格描述用其 {@code (描述)正文} voice-design 语法拼进
 * {@link #input}（见 {@link VoxCpmNarrationEngine}）。
 * <p>
 * <b>克隆有两条线缆形态，由 {@code clone-mode} 决定：</b>
 * <ul>
 *   <li><b>可控克隆</b>（{@link #controllableClone}，默认）：只给 {@link #refAudio}（<b>不带</b> {@link #refText}），
 *       VoxCPM2 按「隔离参考音」克隆音色，{@code input} 里的 {@code (情绪)} 控制指令照常生效。</li>
 *   <li><b>Hi-Fi 续写</b>（{@link #hifiClone}）：同时下发 {@link #refAudio} 与 {@link #refText}（参考音转录），
 *       服务端切到 Ultimate/Hi-Fi 音频续写模式——保真度最高，但<b>忽略</b> {@code input} 里的控制指令；仅在角色参考音
 *       确有转录、且配置 {@code clone-mode=hifi} 时使用。</li>
 * </ul>
 * <p>
 * {@link #maxNewTokens} 是生成 token 上限（防跑飞兜底，含已知 bug vllm-omni#2896：克隆停止符可能不触发），
 * <b>克隆与 voice-design 两条路径都下发</b>。克隆字段与 {@code maxNewTokens} 均 {@link JsonInclude.Include#NON_NULL}，
 * 为空时不出现在请求体中。
 *
 * @param model          模型名
 * @param input          合成文本（已含内联 voice-design / delivery 前缀）
 * @param voice          voice id（可空：留空则不下发 {@code voice} 字段）
 * @param responseFormat 音频输出格式（{@code wav} / {@code pcm}），线缆字段名 {@code response_format}
 * @param refAudio       参考音（{@code data:audio/...;base64,...}），线缆字段名 {@code ref_audio}；非克隆时为 {@code null}
 * @param refText        参考音转录文本，线缆字段名 {@code ref_text}；<b>仅</b> Hi-Fi 续写路径下发，可控克隆路径保持 {@code null}
 * @param maxNewTokens   生成 token 上限（停止符兜底），线缆字段名 {@code max_new_tokens}；{@code null} 表示不设上限
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

    /** 纯 voice-design（无克隆）请求体：克隆字段留空，仅带可选生成 token 上限（防跑飞兜底）。 */
    public static VoxCpmSpeechRequest voiceDesign(String model, String input, String voice,
                                                  String responseFormat, Integer maxNewTokens) {
        return new VoxCpmSpeechRequest(model, input, voice, responseFormat, null, null, maxNewTokens);
    }

    /**
     * 可控克隆（Controllable Cloning）请求体：带 {@code ref_audio} 但<b>不带</b> {@code ref_text}，使 VoxCPM2 按
     * 「隔离参考音」克隆音色、保留 {@code input} 里的 {@code (情绪)} 控制指令。绝不下发转录，避免落入忽略控制指令、
     * 且会吞首句 / 出空音频的 Hi-Fi 续写模式。
     */
    public static VoxCpmSpeechRequest controllableClone(String model, String input, String voice,
                                                        String responseFormat, String refAudio, Integer maxNewTokens) {
        return new VoxCpmSpeechRequest(model, input, voice, responseFormat, refAudio, null, maxNewTokens);
    }

    /**
     * Hi-Fi 续写请求体：<b>同时</b>下发 {@code ref_audio} 与 {@code ref_text}（参考音转录），使 VoxCPM2 进入
     * Ultimate/Hi-Fi 音频续写模式——保真度最高，但<b>忽略</b> {@code input} 里的 {@code (情绪)} 控制指令。仅在角色
     * 参考音确有转录、且配置 {@code clone-mode=hifi} 时使用。
     */
    public static VoxCpmSpeechRequest hifiClone(String model, String input, String voice, String responseFormat,
                                                String refAudio, String refText, Integer maxNewTokens) {
        return new VoxCpmSpeechRequest(model, input, voice, responseFormat, refAudio, refText, maxNewTokens);
    }
}
