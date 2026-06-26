package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 受信 catalog 测试公共支撑：本地 loopback HTTP 桩（JDK 自带 {@code com.sun.net.httpserver}，不引第三方依赖、不触真实
 * 网络）+ 合成插件包字节 + sha256 计算。HTTP 桩只跑在 {@code 127.0.0.1} 上，测试用「放开非公网地址」的 HTTP 客户端对接。
 */
final class CatalogTestSupport {

    private CatalogTestSupport() {
    }

    /** 启动一个 loopback HTTP 桩（端口随机）。 */
    static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int port(HttpServer server) {
        return server.getAddress().getPort();
    }

    /** {@code http://127.0.0.1:<port><path>} —— 强制走 loopback 字面量（不触发 DNS）。 */
    static String loopbackUrl(HttpServer server, String path) {
        return "http://127.0.0.1:" + port(server) + path;
    }

    /** 在某路径以 200 返回固定字节。 */
    static void serveBytes(HttpServer server, String path, byte[] body) {
        server.createContext(path, exchange -> respond(exchange, 200, body));
    }

    /** 在某路径以指定状态码返回空体（无 Location）。 */
    static void serveStatus(HttpServer server, String path, int status) {
        server.createContext(path, exchange -> {
            try {
                exchange.sendResponseHeaders(status, -1);
            } finally {
                exchange.close();
            }
        });
    }

    /** 在某路径返回 302 重定向到 {@code location}（用于验证「禁用重定向」）。 */
    static void serveRedirect(HttpServer server, String path, String location) {
        server.createContext(path, exchange -> {
            try {
                exchange.getResponseHeaders().add("Location", location);
                exchange.sendResponseHeaders(302, -1);
            } finally {
                exchange.close();
            }
        });
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    /** 一份合法的解压目录形态插件包字节（根 {@code plugin.properties} + {@code classes/} 负载，可被安装器接受）。 */
    static byte[] explodedPluginZip(String id, String version, String requires) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.properties", props(id, version, requires));
        entries.put("classes/", new byte[0]);
        entries.put("classes/Marker.class", "fake-class".getBytes(StandardCharsets.UTF_8));
        return zipBytes(entries);
    }

    private static byte[] props(String id, String version, String requires) {
        StringBuilder sb = new StringBuilder();
        sb.append("plugin.id=").append(id).append('\n');
        sb.append("plugin.version=").append(version).append('\n');
        sb.append("plugin.class=com.example.Plugin").append('\n');
        if (requires != null) {
            sb.append("plugin.requires=").append(requires).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                byte[] content = entry.getValue();
                if (content.length > 0) {
                    zos.write(content);
                }
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    /** 计算字节的 SHA-256 十六进制小写串。 */
    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
