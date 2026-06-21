package top.sywyar.pixivdownload.tts.dto;

/**
 * Edge TTS 合成请求体。
 *
 * @param text   要朗读的纯文本
 * @param voice  语音 ShortName（必须命中后端语音列表）
 * @param rate   语速百分比，整数，范围由后端裁剪（如 {@code -20}、{@code 50}）
 * @param pitch  音调 Hz 偏移，整数（如 {@code 0}、{@code -10}）
 */
public record EdgeTtsSynthesizeRequest(String text, String voice, Integer rate, Integer pitch) {
}
