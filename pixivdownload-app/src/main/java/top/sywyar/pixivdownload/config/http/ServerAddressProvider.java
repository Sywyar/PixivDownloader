package top.sywyar.pixivdownload.config.http;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.SslConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Function;

/** 当前实例地址的唯一构造入口；监听事实不回写配置，域名仍支持热更新。 */
@Component
@RequiredArgsConstructor
public class ServerAddressProvider {
    private final Environment environment;
    private final SslConfig sslConfig;
    private volatile URI listener;

    @EventListener
    public void onWebServerInitialized(ServletWebServerInitializedEvent event) {
        // 管理端口的子上下文不能覆盖应用的主监听地址。
        if (event.getApplicationContext().getServerNamespace() != null) return;
        String scheme = event.getWebServer() instanceof TomcatWebServer tomcat
                ? tomcat.getTomcat().getConnector().getScheme() : configuredScheme(environment::getProperty);
        listener = baseUri(scheme, "localhost", event.getWebServer().getPort());
    }

    public int port() {
        URI bound = listener;
        if (bound != null) return bound.getPort() == -1 ? ("https".equals(bound.getScheme()) ? 443 : 80) : bound.getPort();
        return environment.getProperty("local.server.port", Integer.class,
                environment.getProperty("server.port", Integer.class, 6999));
    }

    public URI baseUri() {
        URI bound = listener;
        return baseUri(bound == null ? configuredScheme(environment::getProperty) : bound.getScheme(),
                sslConfig.getDomain(), port());
    }

    public URI uri(String path) {
        return resolve(baseUri(), path);
    }

    /** 后端尚未启动时的桌面预览仍由同一入口解释配置。 */
    public static URI configuredBaseUri(Function<String, String> property, int startupPort) {
        return baseUri(configuredScheme(property), property.apply("ssl.domain"), startupPort);
    }

    public static URI resolve(URI base, String path) {
        String suffix = path == null || path.isBlank() ? "" : path.startsWith("/") ? path : "/" + path;
        return URI.create(base.toASCIIString() + suffix);
    }

    private static String configuredScheme(Function<String, String> property) {
        return Boolean.parseBoolean(property.apply("server.ssl.enabled")) ? "https" : "http";
    }

    private static URI baseUri(String scheme, String domain, int port) {
        String host = domain == null || domain.isBlank() ? "localhost" : domain.trim();
        if (host.contains("/") || host.contains("\\") || host.contains("@")
                || host.contains("?") || host.contains("#")) host = "localhost";
        int urlPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80) ? -1 : port;
        try {
            return new URI(scheme, null, host, urlPort, null, null, null).parseServerAuthority();
        } catch (URISyntaxException invalidDomain) {
            // 非主机配置不能注入路径或凭据；保留本实例的协议与端口。
            return URI.create(scheme + "://localhost" + (urlPort == -1 ? "" : ":" + urlPort));
        }
    }
}
