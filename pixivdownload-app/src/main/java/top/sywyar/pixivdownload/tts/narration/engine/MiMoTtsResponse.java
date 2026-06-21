package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 小米 MiMo v2.5 TTS 的（非流式）响应体线缆 DTO。MiMo 沿用 OpenAI chat completion 结构，<b>音频以 base64</b>
 * 位于 {@code choices[0].message.audio.data}。只声明取音频所需的字段，其余忽略。
 *
 * @param choices 候选结果（取第一条的音频）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MiMoTtsResponse(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Audio audio) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Audio(String data) {
    }

    /** 取第一条候选的 base64 音频数据；任一层缺失返回 {@code null}。 */
    public String audioData() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice choice = choices.get(0);
        if (choice == null || choice.message() == null || choice.message().audio() == null) {
            return null;
        }
        return choice.message().audio().data();
    }
}
