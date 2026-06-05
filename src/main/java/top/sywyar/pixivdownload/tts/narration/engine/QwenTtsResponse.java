package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 阿里 DashScope Qwen3-TTS 的响应体线缆 DTO。成功时音频以<b>临时签名 URL</b> 位于 {@code output.audio.url}（24 小时内有效，
 * 需再发一次 GET 取字节）；失败时 DashScope 在顶层返回 {@code code} / {@code message}（成功响应不含 {@code code}）。只声明取
 * 音频 URL 与判定状态所需字段，其余忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QwenTtsResponse(Output output, String code, String message) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(Audio audio) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Audio(String url) {
    }

    /** {@code output.audio.url}（临时音频 URL）；任一层缺失返回 {@code null}。 */
    public String audioUrl() {
        if (output == null || output.audio() == null) {
            return null;
        }
        return output.audio().url();
    }

    /** 业务错误码（DashScope 仅在出错时返回；成功为 {@code null}）。 */
    public String errorCode() {
        return code;
    }

    /** 业务错误说明（可空）。 */
    public String errorMessage() {
        return message;
    }
}
