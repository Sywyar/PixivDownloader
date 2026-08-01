package top.sywyar.pixivdownload.plugin.api.http;

/**
 * Network or transport failure. HTTP error statuses are represented by {@link OutboundHttpResponse}.
 */
public class OutboundHttpTransportException extends RuntimeException {

    public OutboundHttpTransportException(String message) {
        super(message);
    }

    public OutboundHttpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
