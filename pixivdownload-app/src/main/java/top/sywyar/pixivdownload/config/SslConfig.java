package top.sywyar.pixivdownload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SSL / HTTPS 相关配置（前缀 {@code ssl}）。
 *
 * <p>HTTPS 证书本身通过标准 {@code server.ssl.*} 属性配置；
 * 本类负责管理域名、证书类型以及额外的重定向行为。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ssl")
public class SslConfig {

    /**
     * 服务对外暴露的域名，用于构造用户可访问的 URL。
     * <p>默认值 {@code localhost} 仅适用于本机访问；若通过域名或公网 IP 访问，
     * 必须将此值修改为对应的域名（如 {@code example.com}）。
     * 该值是代码中构造对外 URL 的唯一来源，禁止在代码中硬编码域名或协议。
     */
    private volatile String domain = "localhost";

    /**
     * 证书类型，对应配置文件中的 {@code ssl.type}。
     * {@code pem}：PEM 证书（{@code server.ssl.certificate} + {@code server.ssl.certificate-private-key}）；
     * {@code jks}：JKS/PKCS12 证书库（{@code server.ssl.key-store} + 相关属性）。
     */
    private volatile String type = "pem";

    /**
     * 是否在 {@link #httpRedirectPort} 监听 HTTP 请求并将其 301 重定向到 HTTPS 端口。
     * 仅在对应证书已配置时生效。
     */
    private volatile boolean httpRedirect = false;

    /**
     * HTTP 重定向监听端口，默认 80。
     */
    private volatile int httpRedirectPort = 80;
}
