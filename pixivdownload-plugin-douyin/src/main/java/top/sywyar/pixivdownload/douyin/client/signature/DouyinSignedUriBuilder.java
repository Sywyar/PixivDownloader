package top.sywyar.pixivdownload.douyin.client.signature;

import top.sywyar.pixivdownload.douyin.client.DouyinRequestHeaders;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DouyinSignedUriBuilder {

    private static final String BASE_URL = "https://www.douyin.com";
    private final DouyinABogusSigner aBogusSigner;
    private final DouyinXBogusSigner xBogusSigner;

    public DouyinSignedUriBuilder() {
        this(new DouyinABogusSigner(DouyinRequestHeaders.USER_AGENT),
                new DouyinXBogusSigner(DouyinRequestHeaders.USER_AGENT));
    }

    DouyinSignedUriBuilder(DouyinABogusSigner signer) {
        this(signer, new DouyinXBogusSigner(DouyinRequestHeaders.USER_AGENT));
    }

    DouyinSignedUriBuilder(DouyinABogusSigner aBogusSigner, DouyinXBogusSigner xBogusSigner) {
        this.aBogusSigner = aBogusSigner;
        this.xBogusSigner = xBogusSigner;
    }

    public URI api(String path, Map<String, ?> endpointParams, String cookie) {
        return apiCandidates(path, endpointParams, cookie).get(0);
    }

    public List<URI> apiCandidates(String path, Map<String, ?> endpointParams, String cookie) {
        LinkedHashMap<String, String> params = defaultQuery(cookie);
        if (endpointParams != null) {
            endpointParams.forEach((key, value) -> params.put(key, value == null ? "" : String.valueOf(value)));
        }
        String query = encodeQuery(params);
        String basePath = BASE_URL + normalizePath(path);
        String unsignedUrl = basePath + "?" + query;
        ArrayList<URI> candidates = new ArrayList<>(2);
        candidates.add(URI.create(basePath + "?" + aBogusSigner.signQuery(query)));
        candidates.add(URI.create(xBogusSigner.sign(unsignedUrl).url()));
        return List.copyOf(candidates);
    }

    private static LinkedHashMap<String, String> defaultQuery(String cookie) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("device_platform", "webapp");
        params.put("aid", "6383");
        params.put("channel", "channel_pc_web");
        params.put("update_version_code", "170400");
        params.put("pc_client_type", "1");
        params.put("pc_libra_divert", "Windows");
        params.put("version_code", "290100");
        params.put("version_name", "29.1.0");
        params.put("cookie_enabled", "true");
        params.put("screen_width", "1536");
        params.put("screen_height", "864");
        params.put("browser_language", "zh-CN");
        params.put("browser_platform", "Win32");
        params.put("browser_name", "Chrome");
        params.put("browser_version", "139.0.0.0");
        params.put("browser_online", "true");
        params.put("engine_name", "Blink");
        params.put("engine_version", "139.0.0.0");
        params.put("os_name", "Windows");
        params.put("os_version", "10");
        params.put("cpu_core_num", "16");
        params.put("device_memory", "8");
        params.put("platform", "PC");
        params.put("downlink", "10");
        params.put("effective_type", "4g");
        params.put("round_trip_time", "200");
        params.put("support_h265", "1");
        params.put("support_dash", "1");
        params.put("uifid", "");
        params.put("msToken", DouyinMsToken.ensure(cookie));
        return params;
    }

    private static String encodeQuery(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return query.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        return value.startsWith("/") ? value : "/" + value;
    }
}
