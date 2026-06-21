package top.sywyar.pixivdownload.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;

/**
 * 根据 {@code ssl.type} 选择性地加载 PEM 或 JKS 证书，并配置 HTTPS 连接器。
 *
 * <p>{@code @Order(1)} 确保本类在 Spring Boot 内置的
 * {@code TomcatWebServerFactoryCustomizer}（{@code @Order(0)}）之后运行，
 * 以干净的 {@link Ssl} 对象覆盖其配置——对象中只含 {@code ssl.type} 指定类型的证书属性，
 * 另一类型的属性完全不读取，从根本上避免跨类型属性干扰。
 *
 * <p>当 {@code ssl.http-redirect=true} 时，额外在 {@code ssl.http-redirect-port}（默认 80）
 * 开启 HTTP 连接器，将所有 HTTP 请求 301 重定向到 HTTPS 端口。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class HttpsWebServerCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final SslConfig sslConfig;
    private final Environment environment;
    private final AppMessages messages;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        boolean sslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);
        if (!sslEnabled) return;

        Ssl ssl = buildSsl();
        if (ssl == null) return;

        // 覆盖 Spring Boot 基于 server.ssl.* 自动装配的 Ssl 对象
        factory.setSsl(ssl);
        log.info(message("https.log.enabled", sslConfig.getType()));

        if (sslConfig.isHttpRedirect()) {
            int httpsPort = environment.getProperty("server.port", Integer.class, 6999);
            int httpPort = sslConfig.getHttpRedirectPort();
            log.info(message("https.log.redirect.enabled", httpPort, httpsPort));
            factory.addAdditionalTomcatConnectors(createHttpConnector(httpPort, httpsPort));
            factory.addContextCustomizers(context -> {
                SecurityConstraint constraint = new SecurityConstraint();
                constraint.setUserConstraint("CONFIDENTIAL");
                SecurityCollection collection = new SecurityCollection();
                collection.addPattern("/*");
                constraint.addCollection(collection);
                context.addConstraint(constraint);
            });
        }
    }

    /**
     * 根据 {@code ssl.type} 仅读取对应类型的证书属性，构建 {@link Ssl} 配置对象。
     * 另一类型的属性不会被访问，返回 {@code null} 表示配置不足、跳过 HTTPS。
     */
    private Ssl buildSsl() {
        String type = sslConfig.getType();
        Ssl ssl = new Ssl();

        if ("pem".equalsIgnoreCase(type)) {
            String cert = environment.getProperty("server.ssl.certificate", "");
            String key  = environment.getProperty("server.ssl.certificate-private-key", "");
            if (cert.isBlank() || key.isBlank()) {
                log.warn(message("https.log.pem.missing-config"));
                return null;
            }
            ssl.setCertificate(cert);
            ssl.setCertificatePrivateKey(key);

        } else if ("jks".equalsIgnoreCase(type)) {
            String keyStore = environment.getProperty("server.ssl.key-store", "");
            if (keyStore.isBlank()) {
                log.warn(message("https.log.jks.missing-config"));
                return null;
            }
            ssl.setKeyStore(keyStore);
            ssl.setKeyStorePassword(environment.getProperty("server.ssl.key-store-password", ""));
            ssl.setKeyStoreType(environment.getProperty("server.ssl.key-store-type", "JKS"));

        } else {
            log.warn(message("https.log.type.invalid", type));
            return null;
        }

        return ssl;
    }

    private Connector createHttpConnector(int httpPort, int httpsPort) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("http");
        connector.setPort(httpPort);
        connector.setSecure(false);
        connector.setRedirectPort(httpsPort);
        return connector;
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
