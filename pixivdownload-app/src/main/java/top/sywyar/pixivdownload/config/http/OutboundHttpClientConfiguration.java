package top.sywyar.pixivdownload.config.http;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientFactory;

/** Host composition root for stable outbound HTTP and WebSocket transports. */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class OutboundHttpClientConfiguration {

    private final ProxyConfig proxyConfig;

    @Bean
    public OutboundHttpClientFactory outboundHttpClientFactory() {
        return new ApacheOutboundHttpClientFactory(proxyConfig);
    }

    @Bean
    public OutboundWebSocketClientFactory outboundWebSocketClientFactory() {
        return new JdkOutboundWebSocketClientFactory(proxyConfig);
    }
}
