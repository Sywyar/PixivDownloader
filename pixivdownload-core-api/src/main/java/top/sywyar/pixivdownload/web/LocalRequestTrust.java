package top.sywyar.pixivdownload.web;

import java.net.URI;

/**
 * 不将共享契约绑定到 Servlet API 的本地请求信任检查。
 */
public final class LocalRequestTrust {

    private LocalRequestTrust() {
    }

    /**
     * 判断可信状态本地值请求是否满足条件。
     *
     * @param remoteAddr {@code remoteAddr} 对应的值
     * @param host 主机
     * @param forwardedFor {@code forwardedFor} 对应的值
     * @param realIp 真实值IP
     * @param forwarded 转发信息
     * @param origin 来源
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isTrustedLocalRequest(String remoteAddr,
                                                String host,
                                                String forwardedFor,
                                                String realIp,
                                                String forwarded,
                                                String origin) {
        return isLocalRequest(remoteAddr, host, forwardedFor, realIp, forwarded)
                && originIsLocalOrAbsent(origin);
    }

    /**
     * 判断本地值请求是否满足条件。
     *
     * @param remoteAddr {@code remoteAddr} 对应的值
     * @param host 主机
     * @param forwardedFor {@code forwardedFor} 对应的值
     * @param realIp 真实值IP
     * @param forwarded 转发信息
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isLocalRequest(String remoteAddr,
                                         String host,
                                         String forwardedFor,
                                         String realIp,
                                         String forwarded) {
        return isLocalAddress(remoteAddr)
                && hostHeaderIsLocal(host)
                && forwardedHeaderIsLocal(forwardedFor)
                && forwardedHeaderIsLocal(realIp)
                && standardForwardedHeaderIsLocal(forwarded);
    }

    /**
     * 判断{@code localAddress} 对应的值是否满足条件。
     *
     * @param remoteAddr {@code remoteAddr} 对应的值
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isLocalAddress(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "localhost".equalsIgnoreCase(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "::ffff:127.0.0.1".equals(remoteAddr);
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param origin 来源
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean originIsLocalOrAbsent(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if ("null".equalsIgnoreCase(origin)) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return host != null && isLocalAddress(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hostHeaderIsLocal(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return true;
        }
        return isLocalAddress(normalizeHostAddress(headerValue));
    }

    private static boolean forwardedHeaderIsLocal(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return true;
        }
        for (String part : headerValue.split(",")) {
            if (!isLocalAddress(normalizeForwardedAddress(part))) {
                return false;
            }
        }
        return true;
    }

    private static boolean standardForwardedHeaderIsLocal(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return true;
        }
        for (String element : headerValue.split(",")) {
            String forwardedFor = null;
            for (String pair : element.split(";")) {
                int equals = pair.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String name = pair.substring(0, equals).trim();
                if ("for".equalsIgnoreCase(name)) {
                    forwardedFor = pair.substring(equals + 1).trim();
                    break;
                }
            }
            if (forwardedFor != null && !isLocalAddress(normalizeForwardedAddress(forwardedFor))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeForwardedAddress(String value) {
        if (value == null) {
            return "";
        }
        String address = value.trim();
        if (address.length() >= 2 && address.startsWith("\"") && address.endsWith("\"")) {
            address = address.substring(1, address.length() - 1).trim();
        }
        if (address.startsWith("[") && address.contains("]")) {
            return address.substring(1, address.indexOf(']'));
        }
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            String port = address.substring(colon + 1);
            if (port.chars().allMatch(Character::isDigit)) {
                return address.substring(0, colon);
            }
        }
        return address;
    }

    private static String normalizeHostAddress(String value) {
        String address = normalizeForwardedAddress(value);
        if (address.endsWith(".")) {
            address = address.substring(0, address.length() - 1);
        }
        return address;
    }
}
