package top.sywyar.pixivdownload.common;

import lombok.experimental.UtilityClass;

/**
 * 网络相关工具方法。
 */
@UtilityClass
public class NetworkUtils {

    /**
     * 判断给定的远程地址是否为本地回环地址。
     * 支持 IPv4 和 IPv6 格式。
     */
    public static boolean isLocalAddress(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
            || "0:0:0:0:0:0:0:1".equals(remoteAddr)
            || "::1".equals(remoteAddr)
            || "::ffff:127.0.0.1".equals(remoteAddr);
    }
}
