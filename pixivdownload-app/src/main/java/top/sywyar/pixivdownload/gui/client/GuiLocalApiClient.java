package top.sywyar.pixivdownload.gui.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 桌面 GUI 调用本机 {@code /api/gui/**} 端点的 HTTP 客户端。
 *
 * <p>该类统一负责 HTTP/HTTPS 协议探测、GUI token、超时、UTF-8 请求体与 JSON 响应解析；
 * Swing 面板只负责调用时序和视图状态投影。</p>
 */
@Slf4j
public final class GuiLocalApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SSLContext TRUST_ALL_SSL = buildTrustAllSslContext();

    private final int serverPort;
    private final Supplier<String> tokenSupplier;
    private volatile String preferredScheme = "http";

    public GuiLocalApiClient(int serverPort) {
        this(serverPort, GuiTokenHolder::get);
    }

    GuiLocalApiClient(int serverPort, Supplier<String> tokenSupplier) {
        this.serverPort = serverPort;
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    /**
     * 探测状态端点。非 200 响应会立即停止协议探测，避免把 TLS 握手字节发送到 HTTP 端口；
     * 200 的解析结果则分别记录是否收到响应与是否可供视图消费，保持面板既有投影语义。
     */
    public StatusResponse fetchStatus() {
        boolean successful = false;
        for (String scheme : preferredSchemes()) {
            HttpURLConnection connection = null;
            try {
                connection = open(scheme, "/api/gui/status", "GET", 2_000, false);
                int status = connection.getResponseCode();
                preferredScheme = scheme;
                if (status != HttpURLConnection.HTTP_OK) {
                    return new StatusResponse(successful, false, null);
                }
                successful = true;
                try (InputStream input = connection.getInputStream()) {
                    JsonNode body = MAPPER.readTree(input);
                    return new StatusResponse(true, body != null, body);
                }
            } catch (Exception ignored) {
                // 当前协议没有产出可消费的状态响应时尝试备用协议；轮询保持静默，由面板投影状态。
            } finally {
                disconnect(connection);
            }
        }
        return new StatusResponse(successful, false, null);
    }

    public JsonNode exchangeForm(String method,
                                 String path,
                                 String formBody,
                                 int readTimeoutMs,
                                 String failureLogCode) {
        return exchange(method, path, formBody, "application/x-www-form-urlencoded",
                readTimeoutMs, failureLogCode);
    }

    public JsonNode exchangeJson(String method,
                                 String path,
                                 String jsonBody,
                                 int readTimeoutMs,
                                 String failureLogCode) {
        return exchange(method, path, jsonBody, "application/json; charset=utf-8",
                readTimeoutMs, failureLogCode);
    }

    private JsonNode exchange(String method,
                              String path,
                              String body,
                              String contentType,
                              int readTimeoutMs,
                              String failureLogCode) {
        for (String scheme : preferredSchemes()) {
            HttpURLConnection connection = null;
            try {
                connection = open(scheme, path, method, readTimeoutMs, body != null);
                if (body != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                    try (var output = connection.getOutputStream()) {
                        output.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }

                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_NO_CONTENT) {
                    return null;
                }
                try (InputStream input = status >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream()) {
                    if (input == null) {
                        return null;
                    }
                    return MAPPER.readTree(input);
                }
            } catch (Exception e) {
                log.debug(logMessage(failureLogCode, path, e.getMessage()));
            } finally {
                disconnect(connection);
            }
        }
        return null;
    }

    private HttpURLConnection open(String scheme,
                                   String path,
                                   String method,
                                   int readTimeoutMs,
                                   boolean withBody) throws Exception {
        URL url = new URI(scheme + "://localhost:" + serverPort + path).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
            https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
            https.setHostnameVerifier((host, session) -> true);
        }
        connection.setConnectTimeout("/api/gui/status".equals(path) ? 2_000 : 5_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod(method);
        connection.setDoOutput(withBody);
        String token = tokenSupplier.get();
        if (token != null) {
            connection.setRequestProperty(GuiTokenHolder.HEADER_NAME, token);
        }
        return connection;
    }

    private String[] preferredSchemes() {
        return "https".equals(preferredScheme)
                ? new String[]{"https", "http"}
                : new String[]{"http", "https"};
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            connection.disconnect();
        }
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }
                    }
            }, null);
            return context;
        } catch (Exception e) {
            log.warn(logMessage("gui.status.log.trust-all-ssl.failed", e.getMessage()));
            return null;
        }
    }

    public record StatusResponse(boolean successful, boolean responseParsed, JsonNode body) {
    }
}
