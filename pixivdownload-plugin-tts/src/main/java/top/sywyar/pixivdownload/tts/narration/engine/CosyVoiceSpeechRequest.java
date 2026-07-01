package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CosyVoice 的 OpenAI 兼容 {@code POST {base-url}/audio/speech} 请求体线缆 DTO（覆盖 vox-box / Xinference 风格部署）。
 * 在 OpenAI 标准的 {@code model/input/voice/response_format} 之外，CosyVoice 用 {@link #instruct} 承载<b>自然语言风格
 * 指令</b>、用 {@link #referenceAudio} / {@link #referenceText} 承载<b>参考音克隆</b>素材。不同部署字段名略有出入——
 * 本 DTO 采用社区常见命名，可空字段 {@link JsonInclude.Include#NON_NULL} 不下发。
 *
 * @param model          模型名
 * @param input          待合成文本
 * @param voice          预置音色 / 角色（可空则不下发）
 * @param responseFormat 输出格式（{@code wav}/{@code mp3}/{@code pcm}），线缆字段名 {@code response_format}
 * @param instruct       自然语言风格指令（voice-design 放音色画像、克隆放 delivery；可空则不下发）
 * @param referenceAudio 参考音（{@code data:audio/...;base64,...}），线缆字段名 {@code reference_audio}；非克隆为 {@code null}
 * @param referenceText  参考音转录，线缆字段名 {@code reference_text}；可空则不下发
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CosyVoiceSpeechRequest(
        String model,
        String input,
        String voice,
        @JsonProperty("response_format") String responseFormat,
        String instruct,
        @JsonProperty("reference_audio") String referenceAudio,
        @JsonProperty("reference_text") String referenceText
) {

    /** 内联 voice-design：带可选预置 voice 与自然语言 {@code instruct}，不带参考音。 */
    public static CosyVoiceSpeechRequest voiceDesign(String model, String input, String voice,
                                                     String responseFormat, String instruct) {
        return new CosyVoiceSpeechRequest(model, input, voice, responseFormat, instruct, null, null);
    }

    /** 参考音克隆：带 {@code reference_audio}（+ 可选转录、可选 delivery instruct），不下发预置 voice。 */
    public static CosyVoiceSpeechRequest clone(String model, String input, String responseFormat,
                                               String instruct, String referenceAudio, String referenceText) {
        return new CosyVoiceSpeechRequest(model, input, null, responseFormat, instruct, referenceAudio, referenceText);
    }
}
