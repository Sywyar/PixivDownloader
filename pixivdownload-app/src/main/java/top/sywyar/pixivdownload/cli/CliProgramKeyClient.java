package top.sywyar.pixivdownload.cli;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 以管理员会话调用运行中实例的密钥管理接口。
 *
 * <p>只连接调用方给出的实例地址：不读取系统代理、不跟随重定向，写请求不自动重放。
 * 登录取得的会话 Cookie 只保存在本对象内，关闭时注销。</p>
 */
final class CliProgramKeyClient implements AutoCloseable {
    /** 单个响应的读取上限；密钥列表与权限目录远小于该值。 */
    static final int RESPONSE_LIMIT = 1024 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** 终端展示服务端说明时的最大字符数。 */
    static final int MESSAGE_LIMIT = 300;
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private final URI base;
    private final String origin;
    private final Locale locale;
    private final Duration requestTimeout;
    private final HttpClient http;
    private String cookie;

    CliProgramKeyClient(URI base, Locale locale) {
        this(base, locale, REQUEST_TIMEOUT);
    }

    CliProgramKeyClient(URI base, Locale locale, Duration requestTimeout) {
        this.base = base;
        this.origin = base.getScheme() + "://" + base.getRawAuthority();
        this.locale = locale;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** 校验实例地址：只接受带主机的 http / https 地址，去掉末尾斜杠，不接受凭据、查询或片段。 */
    static URI parseBase(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("PROGRAM_KEY_CLI_SERVER");
        URI uri;
        try {
            uri = new URI(text.trim()).parseServerAuthority();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("PROGRAM_KEY_CLI_SERVER", invalid);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("PROGRAM_KEY_CLI_SERVER");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return URI.create(scheme + "://" + uri.getRawAuthority() + path);
    }

    URI base() {
        return base;
    }

    URI resolve(String path) {
        return URI.create(base.toASCIIString() + path);
    }

    /** 以管理员身份登录；失败时保留服务端给出的稳定错误码与说明。 */
    void login(String username, String password) throws Failure {
        var response = exchange("POST", "/api/auth/login",
                Map.of("username", username, "password", password, "rememberMe", false));
        List<String> pairs = new ArrayList<>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0].trim();
            int equals = pair.indexOf('=');
            if (equals > 0 && equals < pair.length() - 1) pairs.add(pair);
        }
        if (pairs.isEmpty()) throw new Failure(Kind.RESPONSE, response.statusCode(), null, null);
        cookie = String.join("; ", pairs);
    }

    JsonNode get(String path) throws Failure {
        return exchange("GET", path, null).body();
    }

    JsonNode send(String method, String path, Object body) throws Failure {
        return exchange(method, path, body).body();
    }

    /** 尽力注销本次会话；注销失败不改变已完成操作的结果。 */
    @Override
    public void close() {
        try {
            if (cookie != null) exchange("POST", "/api/auth/logout", null);
        } catch (Failure ignored) {
            // 会话仍会按服务端会话期限失效。
        } finally {
            cookie = null;
            http.close();
        }
    }

    private Response exchange(String method, String path, Object body) throws Failure {
        HttpRequest.Builder request = HttpRequest.newBuilder(resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Accept-Language", locale.toLanguageTag())
                // 携带会话 Cookie 的写请求须通过同源检查；来源取自本次连接的实例地址。
                .header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] json;
            try {
                json = JSON.writeValueAsBytes(body);
            } catch (JacksonException failure) {
                throw new Failure(Kind.RESPONSE, 0, null, null);
            }
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(json));
        }
        var receivedStatus = new AtomicInteger();
        var pending = http.sendAsync(request.build(), info -> {
            receivedStatus.set(info.statusCode());
            if (info.headers().firstValueAsLong("Content-Length").orElse(-1) > RESPONSE_LIMIT) {
                return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(), input -> {
                    // 先订阅并关闭流，让 HTTP 客户端立即释放仍在发送的响应。
                    try (input) {
                        throw new IllegalArgumentException("Response exceeds size limit");
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
            }
            return HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), RESPONSE_LIMIT);
        });
        HttpResponse<byte[]> response;
        try {
            // JDK 25 的请求期限只覆盖响应头，等待完整响应体也必须共用同一个期限。
            response = pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new Failure(Kind.TIMEOUT, receivedStatus.get(), null, null);
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure(Kind.TRANSPORT, 0, null, null);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                throw new Failure(Kind.CONNECT, 0, null, null);
            }
            if (cause instanceof HttpTimeoutException) throw new Failure(Kind.TIMEOUT, receivedStatus.get(), null, null);
            if (cause instanceof SSLException || cause.getCause() instanceof SSLException) {
                throw new Failure(Kind.TLS, 0, null, null);
            }
            int status = receivedStatus.get();
            throw new Failure(status == 0 ? Kind.TRANSPORT : Kind.RESPONSE, status, null, null);
        }
        int status = response.statusCode();
        JsonNode json = readJson(response);
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("Location").map(CliProgramKeyClient::displayText).orElse(null);
            throw new Failure(Kind.REDIRECT, status, null, location);
        }
        if (status < 200 || status >= 300) {
            throw new Failure(Kind.HTTP, status, field(json, "code"), field(json, "error"));
        }
        return new Response(response, json);
    }

    private static JsonNode readJson(HttpResponse<byte[]> response) throws Failure {
        byte[] bytes = response.body();
        if (bytes.length == 0) return null;
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json") && !type.contains("+json")) {
            if (response.statusCode() >= 200 && response.statusCode() < 300)
                throw new Failure(Kind.RESPONSE, response.statusCode(), null, null);
            return null;
        }
        try {
            return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JacksonException failure) {
            throw new Failure(Kind.RESPONSE, response.statusCode(), null, null);
        }
    }

    private static String field(JsonNode json, String name) {
        JsonNode value = json == null ? null : json.path(name);
        return value != null && value.isString() ? displayText(value.stringValue()) : null;
    }

    /** 服务端文本只作为单行纯文本显示：去掉控制字符并限制长度。 */
    static String displayText(String text) {
        if (text == null) return null;
        StringBuilder clean = new StringBuilder();
        text.codePoints().forEach(point -> clean.appendCodePoint(Character.isISOControl(point) ? ' ' : point));
        String value = clean.toString().strip();
        if (value.isEmpty()) return null;
        return value.length() <= MESSAGE_LIMIT ? value : value.substring(0, MESSAGE_LIMIT) + "…";
    }

    private record Response(HttpResponse<byte[]> raw, JsonNode body) {
        java.net.http.HttpHeaders headers() { return raw.headers(); }
        int statusCode() { return raw.statusCode(); }
    }

    /**
     * 请求失败的已知类别；HTTP 失败保留状态码、稳定错误码与服务端说明。
     * CONNECT 表示连接未建立；TRANSPORT 表示连接建立后中断，请求可能已经到达实例。
     */
    enum Kind { CONNECT, TRANSPORT, TIMEOUT, TLS, REDIRECT, HTTP, RESPONSE }

    static final class Failure extends Exception {
        private final Kind kind;
        private final int status;
        private final String code;
        private final String detail;
        private final boolean afterWrite;

        Failure(Kind kind, int status, String code, String detail) {
            this(kind, status, code, detail, false);
        }

        private Failure(Kind kind, int status, String code, String detail, boolean afterWrite) {
            super(kind.name(), null, false, false);
            this.kind = kind;
            this.status = status;
            this.code = code;
            this.detail = detail;
            this.afterWrite = afterWrite;
        }

        Kind kind() { return kind; }
        int status() { return status; }
        String code() { return code; }
        String detail() { return detail; }

        /** 写请求已经发出后，连接中断、超时或响应无法读取都不能说明实例是否已执行。 */
        boolean outcomeUnknown() {
            return afterWrite && (kind == Kind.TRANSPORT || kind == Kind.TIMEOUT || kind == Kind.RESPONSE);
        }

        Failure afterWrite() {
            return new Failure(kind, status, code, detail, true);
        }
    }
}
