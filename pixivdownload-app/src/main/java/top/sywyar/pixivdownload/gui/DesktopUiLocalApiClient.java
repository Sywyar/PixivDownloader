package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.GuiActionInvocationHeaders;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

/** App-owned authenticated transport for local desktop UI requests. */
@Slf4j
final class DesktopUiLocalApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SSLContext TRUST_ALL_SSL = trustAllSslContext();
    private final int serverPort;
    private volatile String preferredScheme = "http";

    DesktopUiLocalApiClient(int serverPort) { this.serverPort = serverPort; }

    DesktopUiHost.GuiResponse exchange(DesktopUiHost.GuiRequest request) {
        for (String scheme : schemes()) {
            HttpURLConnection connection = null;
            try {
                connection = open(scheme, request);
                writeBody(connection, request);
                int status = connection.getResponseCode();
                preferredScheme = scheme;
                Body body = readBody(connection, status, request.maxResponseBytes());
                DesktopUiHost.GuiValue parsed = parse(body.text(), body.limitExceeded());
                return new DesktopUiHost.GuiResponse(true, status, parsed, body.text(), body.limitExceeded());
            } catch (Exception failure) {
                log.debug("Local GUI request failed via {} for {}: {}", scheme, request.path(), failure.toString());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return DesktopUiHost.GuiResponse.unreachable();
    }

    private HttpURLConnection open(String scheme, DesktopUiHost.GuiRequest request) throws Exception {
        var url = new URI(scheme + "://localhost:" + serverPort + request.path()).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
            https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
            https.setHostnameVerifier((host, session) -> true);
        }
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(request.readTimeoutMillis());
        connection.setRequestMethod(request.method());
        connection.setRequestProperty("Accept-Language", request.languageTag());
        String token = GuiTokenHolder.get();
        if (token != null) connection.setRequestProperty(GuiTokenHolder.HEADER_NAME, token);
        if (request.ownerPluginId() != null) {
            connection.setRequestProperty(GuiActionInvocationHeaders.PLUGIN_OWNER, request.ownerPluginId());
        }
        if (request.bodyFormat() != DesktopUiHost.GuiBodyFormat.NONE) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", request.bodyFormat() == DesktopUiHost.GuiBodyFormat.JSON
                    ? "application/json; charset=utf-8"
                    : "application/x-www-form-urlencoded; charset=utf-8");
        }
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, DesktopUiHost.GuiRequest request) throws Exception {
        if (request.bodyFormat() == DesktopUiHost.GuiBodyFormat.NONE) return;
        byte[] bytes = request.bodyFormat() == DesktopUiHost.GuiBodyFormat.JSON
                ? MAPPER.writeValueAsBytes(request.body())
                : String.valueOf(request.body() == null ? "" : request.body()).getBytes(StandardCharsets.UTF_8);
        try (var output = connection.getOutputStream()) { output.write(bytes); }
    }

    private static Body readBody(HttpURLConnection connection, int status, int maxBytes) {
        try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (input == null) return new Body("", false);
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8 * 1024));
            byte[] chunk = new byte[4 * 1024];
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

    private String[] schemes() {
        return "https".equals(preferredScheme) ? new String[]{"https", "http"} : new String[]{"http", "https"};
    }

    private static SSLContext trustAllSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            }}, null);
            return context;
        } catch (Exception failure) {
            log.warn("Failed to initialize the local GUI TLS bridge", failure);
            return null;
        }
    }

    private record Body(String text, boolean limitExceeded) { }
}
