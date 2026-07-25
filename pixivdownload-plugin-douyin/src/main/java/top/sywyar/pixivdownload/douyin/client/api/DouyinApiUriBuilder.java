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
        // 只保留标识「抖音 Web 应用」所需的最小客户端身份参数；不伪造任何浏览器/设备/运行环境指纹。
        // 受签名保护的端点需由 DouyinSignedUriBuilder 注入的真实签名器才能通过服务端验签。
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("device_platform", "webapp");
        params.put("aid", "6383");
        params.put("version_code", "290100");
        params.put("version_name", "29.1.0");
        String msToken = DouyinMsToken.ensure(cookie);
        if (!msToken.isEmpty()) {
            params.put("msToken", msToken);
        }
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
