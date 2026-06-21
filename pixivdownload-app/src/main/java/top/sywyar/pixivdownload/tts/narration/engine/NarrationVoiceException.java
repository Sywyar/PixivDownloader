package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 多角色朗读 TTS 合成过程中的可控异常（仿 {@code EdgeTtsException}）。引擎不可用、连接失败、非 2xx、空响应等
 * 都走它。<b>消息必须脱敏、绝不包含 API Key</b>，由调用方（控制器）统一转成 4xx/5xx。
 */
public class NarrationVoiceException extends RuntimeException {
    public NarrationVoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
