package top.sywyar.pixivdownload.tts;

/** Edge TTS 合成 / 语音列表获取过程中的可控异常，由 {@code TtsExceptionHandler} 统一转 4xx/5xx。 */
public class EdgeTtsException extends RuntimeException {
    public EdgeTtsException(String message, Throwable cause) {
        super(message, cause);
    }
}
