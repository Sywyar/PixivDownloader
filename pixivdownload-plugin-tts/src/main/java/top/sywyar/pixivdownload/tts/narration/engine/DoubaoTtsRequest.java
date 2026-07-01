package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 字节火山引擎 豆包 / Seed-TTS（{@code POST {base-url}/api/v1/tts}，非流式「query」路径）的请求体线缆 DTO。火山把鉴权 /
 * 音色 / 文本拆成四个对象：{@link App}（appid + token + cluster）、{@link User}（uid）、{@link Audio}（voice_type / encoding /
 * 可选 emotion）、{@link Request}（reqid + text + {@code operation=query}）。音色由预置 {@link Audio#voiceType} 决定；逐句
 * 情绪经可选 {@link Audio#emotion} + {@link Audio#enableEmotion} 控制（仅支持情绪的音色生效）。响应是 JSON、音频为 base64
 * （{@link DoubaoTtsResponse}）。
 *
 * @param app     应用鉴权对象（appid / token / cluster）
 * @param user    调用方对象（uid，任意非空标识）
 * @param audio   音频参数对象（音色 / 编码 / 情绪）
 * @param request 合成请求对象（reqid / text / operation）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DoubaoTtsRequest(App app, User user, Audio audio, Request request) {

    /**
     * {@code app} 对象。
     *
     * @param appid   应用 appid
     * @param token   应用 token（火山主要以请求头 {@code Authorization: Bearer;<token>} 鉴权，本字段需非空）
     * @param cluster 业务集群（如 {@code volcano_tts}）
     */
    public record App(String appid, String token, String cluster) {
    }

    /**
     * {@code user} 对象。
     *
     * @param uid 调用方标识（任意非空字符串）
     */
    public record User(String uid) {
    }

    /**
     * {@code audio} 对象。
     *
     * @param voiceType     预置音色 id，线缆字段名 {@code voice_type}
     * @param encoding      音频编码 / 格式（{@code mp3} / {@code wav} / {@code pcm} / {@code ogg_opus}）
     * @param emotion       情绪（可空则不下发，用模型默认）
     * @param enableEmotion 是否启用情绪，线缆字段名 {@code enable_emotion}；可空则不下发
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Audio(@JsonProperty("voice_type") String voiceType, String encoding, String emotion,
                        @JsonProperty("enable_emotion") Boolean enableEmotion) {
    }

    /**
     * {@code request} 对象。
     *
     * @param reqid     请求 id（UUID）
     * @param text      待合成文本
     * @param operation 操作类型（HTTP 非流式固定 {@code query}）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(String reqid, String text, String operation) {
    }
}
