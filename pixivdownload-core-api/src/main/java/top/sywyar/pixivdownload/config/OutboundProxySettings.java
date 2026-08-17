package top.sywyar.pixivdownload.config;

/**
 * 可选出站客户端使用的只读代理设置。
 */
public interface OutboundProxySettings {

    /**
     * 判断启用状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isEnabled();

    /**
     * 返回主机。
     *
     * @return 方法返回的字符串
     */
    String getHost();

    /**
     * 返回端口。
     *
     * @return 方法返回的数值
     */
    int getPort();
}
