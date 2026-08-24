package top.sywyar.pixivdownload.douyin.client.redirect;

import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.request.DouyinRequestHeaders;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class OutboundHttpDouyinRedirectClient implements DouyinRedirectClient {

    private final OutboundHttpClient httpClient;

    public OutboundHttpDouyinRedirectClient(OutboundHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public DouyinRedirectResponse get(URI uri) throws DouyinClientException {
        return get(uri, null);
    }

    @Override
    public DouyinRedirectResponse get(URI uri, String cookie) throws DouyinClientException {
        OutboundHttpResponse response = httpClient.exchange(new OutboundHttpRequest(
                uri, "GET", DouyinRequestHeaders.credentials(uri, cookie), new byte[0]));
        return new DouyinRedirectResponse(
                response.statusCode(),
                headerUri(response.headers(), "Location"),
                firstHeader(response.headers(), "Content-Type"),
                response.body());
    }

    private static URI headerUri(Map<String, List<String>> headers, String name) {
        String value = firstHeader(headers, name);
        return value == null || value.isBlank() ? null : URI.create(value);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }
}
