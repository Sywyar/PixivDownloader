package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.http.LocalGuiWebServerCustomizer.Connection;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.GuiActionInvocationHeaders;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** 应用拥有的本地桌面界面请求认证传输层。 */
@Slf4j
final class DesktopUiLocalApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Supplier<Connection> endpoint;

    DesktopUiLocalApiClient(Supplier<Connection> endpoint) { this.endpoint = endpoint; }

    DesktopUiHost.GuiResponse exchange(DesktopUiHost.GuiRequest request) {
        HttpURLConnection connection = null;
        try {
            connection = open(endpoint.get(), request);
            writeBody(connection, request);
            int status = connection.getResponseCode();
            Body body = readBody(connection, status, request.maxResponseBytes());
            DesktopUiHost.GuiValue parsed = parse(body.text(), body.limitExceeded());
            return new DesktopUiHost.GuiResponse(true, status, parsed, body.text(), body.limitExceeded());
        } catch (Exception failure) {
            log.debug("Local GUI request failed for {}: {}", request.path(), failure.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return DesktopUiHost.GuiResponse.unreachable();
    }

    private HttpURLConnection open(Connection endpoint, DesktopUiHost.GuiRequest request) throws Exception {
        var url = new URI("http://127.0.0.1:" + endpoint.port() + request.path()).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(request.readTimeoutMillis());
        connection.setRequestMethod(request.method());
        connection.setRequestProperty("Accept-Language", request.languageTag());
        String token = endpoint.token();
        if (token != null) connection.setRequestProperty(GuiTokenHolder.HEADER_NAME, token);
        if (request.ownerPluginId() != null) {
            connection.setRequestProperty(GuiActionInvocationHeaders.PLUGIN_OWNER, request.ownerPluginId());
        }
        if (request.bodyFormat() != DesktopUiHost.GuiBodyFormat.NONE || "POST".equals(request.method())) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", request.bodyFormat() == DesktopUiHost.GuiBodyFormat.JSON
                    ? "application/json; charset=utf-8"
                    : "application/x-www-form-urlencoded; charset=utf-8");
        }
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, DesktopUiHost.GuiRequest request) throws Exception {
        if (request.bodyFormat() == DesktopUiHost.GuiBodyFormat.NONE && !"POST".equals(request.method())) return;
        byte[] bytes = request.bodyFormat() == DesktopUiHost.GuiBodyFormat.NONE ? new byte[0]
                : request.bodyFormat() == DesktopUiHost.GuiBodyFormat.JSON
                ? MAPPER.writeValueAsBytes(request.body())
                : String.valueOf(request.body() == null ? "" : request.body()).getBytes(StandardCharsets.UTF_8);
        // 流式发送禁止 HttpURLConnection 在响应丢失后自动重放写操作。
        connection.setFixedLengthStreamingMode(bytes.length);
        try (var output = connection.getOutputStream()) { output.write(bytes); }
    }

    private static Body readBody(HttpURLConnection connection, int status, int maxBytes) {
        try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (input == null) return new Body("", false);
            long contentLength = connection.getContentLengthLong();
            int initialSize = (int) Math.min(maxBytes,
                    contentLength < 0 ? 1024 : Math.min(contentLength, 8 * 1024));
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize);
            byte[] chunk = new byte[Math.max(1, Math.min(initialSize, 4 * 1024))];
            int total = 0;
            for (int read; (read = input.read(chunk)) >= 0;) {
                if (read == 0) continue;
                if (total > maxBytes - read) return new Body("", true);
                output.write(chunk, 0, read);
                total += read;
            }
            return new Body(output.toString(StandardCharsets.UTF_8), false);
        } catch (Exception ignored) {
            return new Body("", false);
        }
    }

    private static DesktopUiHost.GuiValue parse(String body, boolean limitExceeded) {
        if (limitExceeded || body == null || body.isBlank()) return null;
        try { return DesktopUiHost.GuiValue.of(MAPPER.readValue(body, Object.class)); }
        catch (Exception ignored) { return null; }
    }

    private record Body(String text, boolean limitExceeded) { }
}
