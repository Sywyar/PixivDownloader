package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 字节火山引擎 豆包 / Seed-TTS 的（非流式）响应体线缆 DTO。音频以 <b>base64 字符串</b>位于 {@code data}；调用状态在
 * {@code code}（{@code 3000} 为成功）。只声明取音频与判定状态所需字段，其余忽略。
 *
 * @param code    业务状态码（{@code 3000}=成功）
 * @param message 状态说明（可空）
 * @param data    base64 音频数据（可空）
 * @param reqid   请求 id（回显，可空）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DoubaoTtsResponse(int code, String message, String data, String reqid) {

    /** 是否成功（{@code code==3000}）。 */
    public boolean ok() {
        return code == 3000;
    }
}
