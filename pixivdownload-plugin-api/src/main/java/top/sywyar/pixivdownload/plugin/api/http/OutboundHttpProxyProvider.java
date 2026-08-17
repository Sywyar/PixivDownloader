package top.sywyar.pixivdownload.plugin.api.http;

import java.net.URI;

/** 在请求时解析插件自有的显式 HTTP 代理端点。 */
@FunctionalInterface
public interface OutboundHttpProxyProvider {

    /**
     * 返回 {@code http://host:port} 形式的代理 URI；没有有效端点时返回 {@code null}。
     *
     * @return 代理 URI，或没有有效端点时返回 {@code null}
     */
    URI resolveProxyUri();
}
