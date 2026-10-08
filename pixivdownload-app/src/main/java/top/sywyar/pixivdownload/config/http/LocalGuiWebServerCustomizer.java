package top.sywyar.pixivdownload.config.http;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;

import java.io.IOException;

/** 随后端实例启停的 GUI 专用回环入口，不参与公开地址与 TLS 配置。 */
@Component
@Order(3)
@ConditionalOnProperty(name = "pixivdownload.headless", havingValue = "false")
public final class LocalGuiWebServerCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    private static final String REQUEST_ATTRIBUTE = LocalGuiWebServerCustomizer.class.getName();
    private static final Object LOCAL_CONNECTOR = new Object();
    private final GuiTokenProvider tokens;
    private final Connector connector = new Connector(Http11NioProtocol.class.getName());

    public LocalGuiWebServerCustomizer(GuiTokenProvider tokens) {
        this.tokens = tokens;
        connector.setPort(0);
        connector.setScheme("http");
        connector.setSecure(false);
        connector.setRedirectPort(-1);
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        // 固定 IPv4 字面地址，避免 DNS、系统代理及 IPv6 优先级影响内部连接。
        connector.setProperty("address", "127.0.0.1");
        protocol.setMaxThreads(16);
        protocol.setMinSpareThreads(2);
        protocol.setMaxConnections(64);
        protocol.setAcceptCount(16);
        protocol.setConnectionTimeout(2_000);
        protocol.setKeepAliveTimeout(2_000);
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addAdditionalTomcatConnectors(connector);
        factory.addEngineValves(new ValveBase(true) {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                if (request.getConnector() == connector) {
                    if (SafeRequestPath.resolve(request).filter(path -> path.startsWith("/api/gui/")).isEmpty()) {
                        response.setStatus(404);
                        response.setContentLength(0);
                        return;
                    }
                    request.setAttribute(REQUEST_ATTRIBUTE, LOCAL_CONNECTOR);
                }
                getNext().invoke(request, response);
            }
        });
    }

    /** 请求头与请求端口不能伪造由连接器身份产生的内部标记。 */
    public static boolean isLocalGuiRequest(ServletRequest request) {
        return request.getAttribute(REQUEST_ATTRIBUTE) == LOCAL_CONNECTOR;
    }

    public Connection connection() {
        int port = connector.getLocalPort();
        if (connector.getState() != LifecycleState.STARTED || port <= 0) {
            throw new IllegalStateException("Local GUI connector is not running");
        }
        return new Connection(port, tokens.getToken());
    }

    /** 端口和令牌始终属于同一后端实例，不持久化且不输出令牌。 */
    public record Connection(int port, String token) {
        @Override
        public String toString() {
            return "LocalGuiConnection[port=" + port + "]";
        }
    }
}
