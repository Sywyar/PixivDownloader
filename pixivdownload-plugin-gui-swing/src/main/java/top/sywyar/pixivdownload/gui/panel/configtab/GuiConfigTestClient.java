package top.sywyar.pixivdownload.gui.panel.configtab;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.common.web.GuiActionInvocationHeaders;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

/**
 * 配置面板向本地后端 {@code /api/gui/*} 端点发起测试 / 热重载请求的统一客户端。
 * <p>
 * 集中了原先散落在各 {@code postXxx} 方法里的重复样板：同时尝试 http 与 https（后端当前可能是其一）、
 * 连接超时固定 2s、读超时由调用方按端点指定、自动附带 GUI token 与 {@code Accept-Language}、trust-all SSL。
 * 连接不上时 {@link Response#reachable()} 为 false；具体的请求体构造与响应解析仍由各 section 负责。
 */
@Slf4j
public final class GuiConfigTestClient {

    private static final SSLContext TRUST_ALL_SSL = buildTrustAllSslContext();
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int MAX_POST_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_GET_RESPONSE_BYTES = 1024 * 1024;
    private static final String[] SCHEMES = {"http", "https"};

    private final int serverPort;

    public GuiConfigTestClient(int serverPort) {
        this.serverPort = serverPort;
    }

    /** 一次 POST 的结果。reachable=false 表示后端连接不上；否则带回 HTTP 状态码与响应正文。 */
    public record Response(boolean reachable, int status, String body, boolean bodyLimitExceeded) {
        public Response(boolean reachable, int status, String body) {
            this(reachable, status, body, false);
        }

        public boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    /** 向 {@code /api/gui/<endpoint>} POST 一段 JSON；连接不上返回 reachable=false。 */
    public Response postJson(String endpoint, byte[] body, int readTimeoutMs) {
        return postJson(endpoint, body, readTimeoutMs, null);
    }

    /**
     * 向插件声明的 {@code /api/gui/<endpoint>} POST 一段 JSON，并携带宿主聚合时绑定的插件 owner。
     * 后端会把 owner 与当前实际发布的精确 GUI POST 路由再次比对，避免可变 contribution getter
     * 在聚合与发布之间把动作端点切换到其它插件。
     */
    public Response postJson(String endpoint, byte[] body, int readTimeoutMs, String pluginOwner) {
        for (String scheme : SCHEMES) {
            HttpURLConnection conn = null;
            try {
                conn = open(scheme, endpoint, readTimeoutMs, "POST", true);
                if (pluginOwner != null && !pluginOwner.isBlank()) {
                    conn.setRequestProperty(GuiActionInvocationHeaders.PLUGIN_OWNER, pluginOwner);
                }
                conn.getOutputStream().write(body);
                int status = conn.getResponseCode();
                ResponseBody responseBody = readResponseBody(conn, status, MAX_POST_RESPONSE_BYTES);
                return new Response(true, status, responseBody.body(), responseBody.limitExceeded());
            } catch (Exception ignored) {
                // try the other scheme
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return new Response(false, 0, null);
    }

    /** 向 {@code /api/gui/<endpoint>} 发 GET 并取回 JSON 正文；连接不上返回 reachable=false。 */
    public Response getJson(String endpoint, int readTimeoutMs) {
        for (String scheme : SCHEMES) {
            HttpURLConnection conn = null;
            try {
                conn = open(scheme, endpoint, readTimeoutMs, "GET", false);
                int status = conn.getResponseCode();
                ResponseBody responseBody = readResponseBody(conn, status, MAX_GET_RESPONSE_BYTES);
                return new Response(true, status, responseBody.body(), responseBody.limitExceeded());
            } catch (Exception ignored) {
                // try the other scheme
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return new Response(false, 0, null);
    }

    /** 向 {@code /api/gui/<endpoint>} 发一个无正文 POST；返回是否连通且 2xx。 */
    public boolean post(String endpoint, int readTimeoutMs) {
        for (String scheme : SCHEMES) {
            HttpURLConnection conn = null;
            try {
                conn = open(scheme, endpoint, readTimeoutMs, "POST", false);
                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    return true;
                }
            } catch (Exception ignored) {
                // Try the other scheme; the backend may currently be HTTP or HTTPS.
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return false;
    }

    private HttpURLConnection open(String scheme, String endpoint, int readTimeoutMs, String method, boolean withBody)
            throws IOException, java.net.URISyntaxException {
        URL url = new URI(scheme + "://localhost:" + serverPort + "/api/gui/" + endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
            https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
            https.setHostnameVerifier((host, session) -> true);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod(method);
        if (withBody) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        }
        conn.setRequestProperty("Accept-Language", GuiMessages.currentLocale().toLanguageTag());
        String guiToken = GuiTokenHolder.get();
        if (guiToken != null) {
            conn.setRequestProperty(GuiTokenHolder.headerName(), guiToken);
        }
        return conn;
    }

    private static ResponseBody readResponseBody(HttpURLConnection conn, int status, int maxBytes) {
        try (var stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
            if (stream == null) {
                return new ResponseBody("", false);
            }
            return readBoundedUtf8(stream, maxBytes);
        } catch (IOException e) {
            return new ResponseBody("", false);
        }
    }

    private static ResponseBody readBoundedUtf8(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8 * 1024));
        byte[] chunk = new byte[4 * 1024];
        int total = 0;
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > maxBytes - read) {
                return new ResponseBody("", true);
            }
            buffer.write(chunk, 0, read);
            total += read;
        }
        return new ResponseBody(buffer.toString(StandardCharsets.UTF_8), false);
    }

    private record ResponseBody(String body, boolean limitExceeded) {
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context;
        } catch (Exception e) {
            log.warn("Failed to create trust-all SSL context", e);
            return null;
        }
    }
}
