package top.sywyar.pixivdownload.plugin.api.schedule.network;

/**
 * 计划任务网络路由选择。{@link Mode#INHERIT} 是持久化配置值；宿主必须先用
 * {@link #resolveAgainst(ScheduledNetworkRoute)} 解析成 {@link Mode#DIRECT} 或 {@link Mode#PROXY}，再把同一个
 * resolved route 对象传给来源发现、作品执行、凭证探活与执行 Guard。插件把 resolved route 应用到自己的 API、
 * 重定向和媒体客户端。
 *
 * <p>{@code proxyCredentialReference} 只是宿主凭证存储中的不透明引用，不是代理密码；代理密钥不得进入本记录。
 */
public record ScheduledNetworkRoute(
        Mode mode,
        String proxyHost,
        int proxyPort,
        String proxyCredentialReference
) {

    /** 网络路由模式。 */
    public enum Mode {
        /**
         * 表示 {@code INHERIT} 状态。
         */
        INHERIT,
        /**
         * 表示 {@code DIRECT} 状态。
         */
        DIRECT,
        /** 使用指定代理。 */
        PROXY
    }

    /**
     * 创建并校验计划任务网络路由。
     *
     * @param mode 路由模式
     * @param proxyHost 代理主机
     * @param proxyPort 代理端口
     * @param proxyCredentialReference 代理凭证的不透明引用
     */
    public ScheduledNetworkRoute {
        if (mode == null) {
            throw new IllegalArgumentException("network route mode must not be null");
        }
        if (mode == Mode.PROXY) {
            if (proxyHost == null || proxyHost.isBlank()) {
                throw new IllegalArgumentException("proxy route host must not be blank");
            }
            if (proxyPort < 1 || proxyPort > 65_535) {
                throw new IllegalArgumentException("proxy route port must be between 1 and 65535");
            }
            proxyHost = proxyHost.trim();
            proxyCredentialReference = normalizeNullable(proxyCredentialReference);
        } else if (proxyHost != null || proxyPort != 0 || proxyCredentialReference != null) {
            throw new IllegalArgumentException("non-proxy route must not carry proxy details");
        }
    }

    /**
     * 返回对应值。
     *
     * @return 方法返回的 {@code ScheduledNetworkRoute} 实例
     */
    public static ScheduledNetworkRoute inherit() {
        return new ScheduledNetworkRoute(Mode.INHERIT, null, 0, null);
    }

    /**
     * 返回直连。
     *
     * @return 方法返回的 {@code ScheduledNetworkRoute} 实例
     */
    public static ScheduledNetworkRoute direct() {
        return new ScheduledNetworkRoute(Mode.DIRECT, null, 0, null);
    }

    /**
     * 执行代理并返回结果。
     *
     * @param host 主机
     * @param port 端口
     * @param credentialReference 凭证引用
     * @return 方法返回的 {@code ScheduledNetworkRoute} 实例
     */
    public static ScheduledNetworkRoute proxy(String host, int port, String credentialReference) {
        return new ScheduledNetworkRoute(Mode.PROXY, host, port, credentialReference);
    }

    /**
     * 执行上下文可直接应用的路由不再包含 {@link Mode#INHERIT}。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isResolved() {
        return mode != Mode.INHERIT;
    }

    /**
     * 用宿主已解析的全局默认路由消解 {@link Mode#INHERIT}。显式 DIRECT/PROXY 保持原对象，便于所有调用点共享身份。
     *
     * @param inheritedRoute 继承的路由
     * @return 方法返回的 {@code ScheduledNetworkRoute} 实例
     */
    public ScheduledNetworkRoute resolveAgainst(ScheduledNetworkRoute inheritedRoute) {
        if (mode != Mode.INHERIT) {
            return this;
        }
        if (inheritedRoute == null || !inheritedRoute.isResolved()) {
            throw new IllegalArgumentException("inherited route must already be resolved");
        }
        return inheritedRoute;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
