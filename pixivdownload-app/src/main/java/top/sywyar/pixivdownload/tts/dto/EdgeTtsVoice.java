package top.sywyar.pixivdownload.tts.dto;

/**
 * 一个 Edge TTS 语音条目（前端语音下拉用）。
 *
 * @param shortName 语音标识，如 {@code zh-CN-XiaoxiaoNeural}（合成时传给后端）
 * @param locale    语言区域，如 {@code zh-CN}
 * @param gender    性别（Female/Male）
 * @param localName 友好名称，如 {@code Xiaoxiao}
 */
public record EdgeTtsVoice(String shortName, String locale, String gender, String localName) {
}
