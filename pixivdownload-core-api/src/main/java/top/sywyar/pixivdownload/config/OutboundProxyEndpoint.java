package top.sywyar.pixivdownload.config;

/**
 * 仅依赖 JDK 的出站 HTTP 代理端点。
 */
public record OutboundProxyEndpoint(String hostName, int port) {

    /**
     * 创建 {@code OutboundProxyEndpoint} 实例。
     *
     * @param hostName 主机名称
     * @param port 端口
     */
    public OutboundProxyEndpoint {
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalArgumentException("hostName must not be blank");
        }
        hostName = hostName.trim();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    /**
     * 返回主机名称。
     *
     * @return 方法返回的字符串
     */
    public String getHostName() {
        return hostName;
    }

    /**
     * 返回端口。
     *
     * @return 方法返回的数值
     */
    public int getPort() {
        return port;
    }
}
