package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 阿里 DashScope（百炼）Qwen3-TTS（{@code POST {base-url}/services/aigc/multimodal-generation/generation}）的请求体线缆
 * DTO。DashScope 多模态语音合成把文本 / 音色放在 {@link Input} 下：{@code input.text} 为待合成文本、{@code input.voice}
 * 选预置音色（如 {@code Cherry}）、可选 {@code input.language_type} 提示文本语言以保证发音 / 语调自然。音频在响应里以
 * <b>临时 URL</b> 返回（{@link QwenTtsResponse}），需再发一次 GET 取字节。
 *
 * @param model 模型名（如 {@code qwen3-tts-flash}）
 * @param input 输入对象（text / voice / language_type）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QwenTtsRequest(String model, Input input) {

    /**
     * {@code input} 对象。
     *
     * @param text         待合成文本
     * @param voice        预置音色 id（可空则不下发，用模型默认）
     * @param languageType 文本语言提示（{@code language_type}，如 {@code Chinese} / {@code English}；可空则不下发，自动判别）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(String text, String voice, @JsonProperty("language_type") String languageType) {
    }
}
