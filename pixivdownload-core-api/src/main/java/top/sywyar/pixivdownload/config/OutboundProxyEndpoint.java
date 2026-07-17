package top.sywyar.pixivdownload.config;

/**
 * Pure-JDK outbound HTTP proxy endpoint.
 */
public record OutboundProxyEndpoint(String hostName, int port) {

    public OutboundProxyEndpoint {
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalArgumentException("hostName must not be blank");
        }
        hostName = hostName.trim();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    public String getHostName() {
        return hostName;
    }

    public int getPort() {
        return port;
    }
}
