package top.sywyar.pixivdownload.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;

/**
 * Centralized browser-like headers for requests sent to Pixiv and Pixiv CDN hosts.
 */
public final class PixivRequestHeaders {

    public static final String PIXIV_HOME = "https://www.pixiv.net/";
    public static final String PIXIV_ORIGIN = "https://www.pixiv.net";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String ACCEPT_DOCUMENT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
    private static final String ACCEPT_JSON = "application/json, text/plain, */*";
    private static final String ACCEPT_IMAGE = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7,ja;q=0.6";
    private static final String SEC_CH_UA =
            "\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\", \"Not=A?Brand\";v=\"99\"";

    private PixivRequestHeaders() {
    }

    public static HttpHeaders ajax(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        applyAjax(headers, cookie);
        return headers;
    }

    public static HttpHeaders document(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        applyDocument(headers, cookie);
        return headers;
    }

    public static HttpHeaders image(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        applyImage(headers, cookie);
        return headers;
    }

    public static void applyAjax(HttpHeaders headers, String cookie) {
        applyAjax(writer(headers), cookie);
    }

    public static void applyAjax(java.net.http.HttpRequest.Builder builder, String cookie) {
        applyAjax(writer(builder), cookie);
    }

    public static void applyAjax(org.apache.hc.core5.http.HttpRequest request, String cookie) {
        applyAjax(writer(request), cookie);
    }

    public static void applyDocument(HttpHeaders headers, String cookie) {
        applyDocument(writer(headers), cookie);
    }

    public static void applyDocument(java.net.http.HttpRequest.Builder builder, String cookie) {
        applyDocument(writer(builder), cookie);
    }

    public static void applyDocument(org.apache.hc.core5.http.HttpRequest request, String cookie) {
        applyDocument(writer(request), cookie);
    }

    public static void applyImage(HttpHeaders headers, String cookie) {
        applyImage(writer(headers), PIXIV_HOME, cookie);
    }

    public static void applyImage(java.net.http.HttpRequest.Builder builder, String cookie) {
        applyImage(writer(builder), PIXIV_HOME, cookie);
    }

    public static void applyImage(org.apache.hc.core5.http.HttpRequest request, String cookie) {
        applyImage(writer(request), PIXIV_HOME, cookie);
    }

    public static void applyImage(HttpHeaders headers, String referer, String cookie) {
        applyImage(writer(headers), referer, cookie);
    }

    public static void applyImage(java.net.http.HttpRequest.Builder builder, String referer, String cookie) {
        applyImage(writer(builder), referer, cookie);
    }

    public static void applyImage(org.apache.hc.core5.http.HttpRequest request, String referer, String cookie) {
        applyImage(writer(request), referer, cookie);
    }

    public static void applyOrigin(HttpHeaders headers) {
        headers.set(HttpHeaders.ORIGIN, PIXIV_ORIGIN);
    }

    public static void applyOrigin(java.net.http.HttpRequest.Builder builder) {
        builder.header(HttpHeaders.ORIGIN, PIXIV_ORIGIN);
    }

    public static void applyOrigin(org.apache.hc.core5.http.HttpRequest request) {
        request.setHeader(HttpHeaders.ORIGIN, PIXIV_ORIGIN);
    }

    public static void applyBrowserDefaults(HttpHeaders headers, URI uri, HttpMethod method) {
        if (!isPixivRequest(uri)) {
            return;
        }
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        setIfAbsent(headers, HttpHeaders.REFERER, PIXIV_HOME);
        setIfAbsent(headers, HttpHeaders.ACCEPT_LANGUAGE, ACCEPT_LANGUAGE);
        applyClientHintsIfAbsent(headers);
        if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method)) {
            setIfAbsent(headers, HttpHeaders.ORIGIN, PIXIV_ORIGIN);
        }
        if (!hasHeader(headers, HttpHeaders.ACCEPT)) {
            headers.set(HttpHeaders.ACCEPT, defaultAccept(uri));
        }
        applySecFetchDefaultsIfAbsent(headers, uri);
    }

    private static void applyBase(HttpHeaders headers, String referer, String cookie) {
        applyBase(writer(headers), referer, cookie);
    }

    private static void applyAjax(HeaderWriter headers, String cookie) {
        applyBase(headers, PIXIV_HOME, cookie);
        headers.set(HttpHeaders.ACCEPT, ACCEPT_JSON);
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Sec-Fetch-Dest", "empty");
        headers.set("Sec-Fetch-Mode", "cors");
        headers.set("Sec-Fetch-Site", "same-origin");
    }

    private static void applyDocument(HeaderWriter headers, String cookie) {
        applyBase(headers, PIXIV_HOME, cookie);
        headers.set(HttpHeaders.ACCEPT, ACCEPT_DOCUMENT);
        headers.set("Upgrade-Insecure-Requests", "1");
        headers.set("Sec-Fetch-Dest", "document");
        headers.set("Sec-Fetch-Mode", "navigate");
        headers.set("Sec-Fetch-Site", "none");
        headers.set("Sec-Fetch-User", "?1");
    }

    private static void applyImage(HeaderWriter headers, String referer, String cookie) {
        applyBase(headers, StringUtils.hasText(referer) ? referer : PIXIV_HOME, cookie);
        headers.set(HttpHeaders.ACCEPT, ACCEPT_IMAGE);
        headers.set("Sec-Fetch-Dest", "image");
        headers.set("Sec-Fetch-Mode", "no-cors");
        headers.set("Sec-Fetch-Site", "cross-site");
    }

    private static void applyBase(HeaderWriter headers, String referer, String cookie) {
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.REFERER, referer);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, ACCEPT_LANGUAGE);
        applyClientHints(headers);
        if (StringUtils.hasText(cookie)) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
    }

    private static void applyClientHints(HttpHeaders headers) {
        applyClientHints(writer(headers));
    }

    private static void applyClientHints(HeaderWriter headers) {
        headers.set("Sec-Ch-Ua", SEC_CH_UA);
        headers.set("Sec-Ch-Ua-Mobile", "?0");
        headers.set("Sec-Ch-Ua-Platform", "\"Windows\"");
    }

    private static void applyClientHintsIfAbsent(HttpHeaders headers) {
        setIfAbsent(headers, "Sec-Ch-Ua", SEC_CH_UA);
        setIfAbsent(headers, "Sec-Ch-Ua-Mobile", "?0");
        setIfAbsent(headers, "Sec-Ch-Ua-Platform", "\"Windows\"");
    }

    private static void applySecFetchDefaultsIfAbsent(HttpHeaders headers, URI uri) {
        if (isImageHost(uri)) {
            setIfAbsent(headers, "Sec-Fetch-Dest", "image");
            setIfAbsent(headers, "Sec-Fetch-Mode", "no-cors");
            setIfAbsent(headers, "Sec-Fetch-Site", "cross-site");
            return;
        }
        if (isAjaxPath(uri)) {
            setIfAbsent(headers, "Sec-Fetch-Dest", "empty");
            setIfAbsent(headers, "Sec-Fetch-Mode", "cors");
            setIfAbsent(headers, "Sec-Fetch-Site", "same-origin");
            return;
        }
        setIfAbsent(headers, "Sec-Fetch-Dest", "document");
        setIfAbsent(headers, "Sec-Fetch-Mode", "navigate");
        setIfAbsent(headers, "Sec-Fetch-Site", "none");
    }

    private static String defaultAccept(URI uri) {
        if (isImageHost(uri)) {
            return ACCEPT_IMAGE;
        }
        return isAjaxPath(uri) ? ACCEPT_JSON : ACCEPT_DOCUMENT;
    }

    private static boolean isAjaxPath(URI uri) {
        String path = uri == null ? null : uri.getPath();
        return path != null && (path.startsWith("/ajax/") || path.startsWith("/rpc/"));
    }

    public static boolean isPixivRequest(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return isPixivSiteHost(host) || isImageHost(host);
    }

    private static boolean isImageHost(URI uri) {
        return uri != null && uri.getHost() != null && isImageHost(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private static boolean isPixivSiteHost(String host) {
        return "pixiv.net".equals(host) || host.endsWith(".pixiv.net");
    }

    private static boolean isImageHost(String host) {
        return "pximg.net".equals(host) || host.endsWith(".pximg.net");
    }

    private static void setIfAbsent(HttpHeaders headers, String name, String value) {
        if (!hasHeader(headers, name)) {
            headers.set(name, value);
        }
    }

    private static boolean hasHeader(HttpHeaders headers, String name) {
        return StringUtils.hasText(headers.getFirst(name));
    }

    private static HeaderWriter writer(HttpHeaders headers) {
        return headers::set;
    }

    private static HeaderWriter writer(java.net.http.HttpRequest.Builder builder) {
        return (name, value) -> builder.header(name, value);
    }

    private static HeaderWriter writer(org.apache.hc.core5.http.HttpRequest request) {
        return (name, value) -> request.setHeader(name, value);
    }

    @FunctionalInterface
    private interface HeaderWriter {
        void set(String name, String value);
    }
}
