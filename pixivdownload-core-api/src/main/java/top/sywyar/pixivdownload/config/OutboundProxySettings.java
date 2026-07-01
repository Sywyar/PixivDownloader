package top.sywyar.pixivdownload.config;

/**
 * Read-only proxy settings consumed by optional outbound clients.
 */
public interface OutboundProxySettings {

    boolean isEnabled();

    String getHost();

    int getPort();
}
