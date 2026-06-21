package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 一句话的合成结果：音频字节 + MIME 类型（如 {@code audio/wav}）。引擎按其输出格式填充 {@link #contentType}，
 * 控制器据此设置响应 {@code Content-Type}。
 *
 * @param data        音频字节（非空、非空数组由引擎保证）
 * @param contentType MIME 类型，如 {@code audio/wav}
 */
public record NarrationAudio(byte[] data, String contentType) {
}
