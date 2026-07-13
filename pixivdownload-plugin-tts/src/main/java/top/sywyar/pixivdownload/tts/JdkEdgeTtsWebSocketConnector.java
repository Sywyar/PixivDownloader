package top.sywyar.pixivdownload.tts;

import top.sywyar.pixivdownload.config.OutboundProxySettings;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** JDK {@link WebSocket} 的 Edge TTS 连接实现。 */
final class JdkEdgeTtsWebSocketConnector implements EdgeTtsWebSocketConnector {

    private final OutboundProxySettings proxyConfig;
    private final EdgeTtsVersionService versionService;

    JdkEdgeTtsWebSocketConnector(OutboundProxySettings proxyConfig, EdgeTtsVersionService versionService) {
        this.proxyConfig = proxyConfig;
        this.versionService = versionService;
    }

    @Override
    public CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener) {
        return buildClient().newWebSocketBuilder()
                .header("User-Agent", versionService.userAgent())
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(uri, listener);
    }

    private HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        if (proxyConfig.isEnabled()) {
            String host = proxyConfig.getHost();
            int port = proxyConfig.getPort();
            if (host != null && !host.isBlank() && port > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            }
        }
        return builder.build();
    }
}
