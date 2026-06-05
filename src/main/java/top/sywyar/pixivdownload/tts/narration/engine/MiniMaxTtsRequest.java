package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MiniMax T2A v2（{@code POST {base-url}/t2a_v2}）的请求体线缆 DTO。MiniMax 无逐请求自然语言音色 / 情绪通道：音色由
 * {@link VoiceSetting#voiceId 预置 voice_id} 决定，情绪取自固定枚举 {@link VoiceSetting#emotion}；输出音频以
 * {@code output_format=hex} 的十六进制字符串返回。
 *
 * @param model        模型名（如 {@code speech-2.8-hd}）
 * @param text         待合成文本
 * @param voiceSetting 音色设定，线缆字段名 {@code voice_setting}
 * @param audioSetting 音频设定，线缆字段名 {@code audio_setting}
 * @param outputFormat 输出形态（固定 {@code hex}），线缆字段名 {@code output_format}
 * @param stream       是否流式（固定 {@code false}）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MiniMaxTtsRequest(
        String model,
        String text,
        @JsonProperty("voice_setting") VoiceSetting voiceSetting,
        @JsonProperty("audio_setting") AudioSetting audioSetting,
        @JsonProperty("output_format") String outputFormat,
        Boolean stream
) {

    /**
     * {@code voice_setting} 对象。
     *
     * @param voiceId 预置音色 id，线缆字段名 {@code voice_id}
     * @param emotion 情绪枚举（可空则不下发，用模型默认）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VoiceSetting(@JsonProperty("voice_id") String voiceId, String emotion) {
    }

    /**
     * {@code audio_setting} 对象。
     *
     * @param format     输出格式（{@code mp3} / {@code wav} / {@code pcm} / {@code flac}）
     * @param sampleRate 采样率，线缆字段名 {@code sample_rate}
     */
    public record AudioSetting(String format, @JsonProperty("sample_rate") int sampleRate) {
    }
}
