package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MiniMax T2A v2 的（非流式）响应体线缆 DTO。音频以<b>十六进制字符串</b>位于 {@code data.audio}；调用状态在
 * {@code base_resp}（{@code status_code==0} 为成功）。只声明取音频与判定状态所需字段，其余忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MiniMaxTtsResponse(Data data, @JsonProperty("base_resp") BaseResp baseResp) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String audio) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BaseResp(@JsonProperty("status_code") int statusCode,
                           @JsonProperty("status_msg") String statusMsg) {
    }

    /** {@code data.audio}（hex 字符串）；缺失返回 {@code null}。 */
    public String audioHex() {
        return data == null ? null : data.audio();
    }

    /** 是否成功（{@code base_resp.status_code==0}，或缺 base_resp 时按成功）。 */
    public boolean ok() {
        return baseResp == null || baseResp.statusCode() == 0;
    }

    /** 业务状态码（缺 base_resp 返回 0）。 */
    public int statusCode() {
        return baseResp == null ? 0 : baseResp.statusCode();
    }

    /** 业务状态说明（可空）。 */
    public String statusMsg() {
        return baseResp == null ? null : baseResp.statusMsg();
    }
}
