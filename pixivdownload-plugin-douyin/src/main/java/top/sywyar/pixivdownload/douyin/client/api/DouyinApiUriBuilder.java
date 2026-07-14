package top.sywyar.pixivdownload.douyin.client.api;

import top.sywyar.pixivdownload.douyin.client.signature.DouyinMsToken;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 按参考客户端的参数顺序构造待签名的抖音 Web API 地址。 */
public final class DouyinApiUriBuilder {

    private static final String BASE_URL = "https://www.douyin.com";
    private static final Set<String> COLLECT_PROFILE_PATHS = Set.of(
            "/aweme/v1/web/collects/list/",
            "/aweme/v1/web/collects/video/list/",
            "/aweme/v1/web/mix/listcollection/");

    public URI api(String path, Map<String, ?> endpointParams) {
        return api(path, endpointParams, null);
    }

    public URI api(String path, Map<String, ?> endpointParams, String cookie) {
        String normalizedPath = normalizePath(path);
        LinkedHashMap<String, String> params = defaultQuery(cookie);
        if (COLLECT_PROFILE_PATHS.contains(normalizedPath)) {
            params.put("version_code", "170400");
            params.put("version_name", "17.4.0");
        }
        if (endpointParams != null) {
            endpointParams.forEach((key, value) ->
                    params.put(key, value == null ? "" : String.valueOf(value)));
        }
        return URI.create(BASE_URL + normalizedPath + "?" + encodeQuery(params));
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
