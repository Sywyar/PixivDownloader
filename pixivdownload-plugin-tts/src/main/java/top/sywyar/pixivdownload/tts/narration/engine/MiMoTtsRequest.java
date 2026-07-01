package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 小米 MiMo v2.5 TTS（{@code POST {base-url}/chat/completions}）的请求体线缆 DTO。MiMo 复用 OpenAI 风格的
 * chat 形态：{@link #messages} 中 {@code user} 角色承载<b>音色描述 / 风格指令</b>（不被朗读），{@code assistant}
 * 角色承载<b>待合成文本</b>（可含 {@code (情绪)} / {@code [效果]} 标签）；{@link #audio} 指定输出格式与 voice
 * （预置音色 id 或 {@code data:URI} 形式的克隆参考音）。
 *
 * @param model    模型名（预置 {@code mimo-v2.5-tts} / 描述 {@code -voicedesign} / 克隆 {@code -voiceclone}）
 * @param messages 消息序列（{@code user} 描述可选、{@code assistant} 文本必有）
 * @param audio    音频参数对象
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MiMoTtsRequest(String model, List<Message> messages, Audio audio) {

    /** 一条 chat 消息：{@code role}（{@code user} / {@code assistant}）+ {@code content}。 */
    public record Message(String role, String content) {
    }

    /**
     * {@code audio} 参数对象。
     *
     * @param format               输出格式（{@code wav} / {@code pcm16}）
     * @param voice                voice：预置音色 id，或克隆模式下的 {@code data:audio/...;base64,...} 参考音；可空则不下发
     * @param optimizeTextPreview  文本预处理开关（仅 voicedesign 模型有意义），线缆字段名 {@code optimize_text_preview}；可空则不下发
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Audio(String format, String voice,
                        @JsonProperty("optimize_text_preview") Boolean optimizeTextPreview) {
    }
}
