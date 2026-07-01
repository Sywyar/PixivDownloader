package top.sywyar.pixivdownload.ai;

/**
 * Controlled AI capability failure. Messages must already be redacted.
 */
public class AiClientException extends Exception {
    public AiClientException(String message) {
        super(message);
    }

    public AiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
