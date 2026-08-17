package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 多角色朗读 TTS 合成过程中的可控异常。引擎不可用、连接失败、非 2xx、空响应等都走它。
 * <b>消息必须脱敏、绝不包含 API Key</b>；调用方负责映射为自己的错误模型。
 */
public class NarrationVoiceException extends RuntimeException {
    /**
     * 创建 {@code NarrationVoiceException} 实例。
     *
     * @param message 消息
     * @param cause {@code cause} 对应的值
     */
    public NarrationVoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
