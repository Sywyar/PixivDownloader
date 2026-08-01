package top.sywyar.pixivdownload.douyin.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

public class RestTemplateDouyinRedirectClient implements DouyinRedirectClient {

    private final RestTemplate restTemplate;

    public RestTemplateDouyinRedirectClient(RestTemplate restTemplate) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    @Override
    public DouyinRedirectResponse get(URI uri) throws DouyinClientException {
        return get(uri, null);
    }

    @Override
    public DouyinRedirectResponse get(URI uri, String cookie) throws DouyinClientException {
        HttpHeaders headers = new HttpHeaders();
        DouyinRequestHeaders.applyCredentials(headers, uri, cookie);
        try {
            return restTemplate.execute(uri, HttpMethod.GET, request -> {
                request.getHeaders().putAll(headers);
            }, RestTemplateDouyinRedirectClient::toResponse);
        } catch (HttpStatusCodeException response) {
            return toResponse(response);
        }
    }

    private static DouyinRedirectResponse toResponse(ClientHttpResponse response) throws IOException {
        byte[] body = response.getBody() == null ? new byte[0] : response.getBody().readAllBytes();
        return toResponse(response.getStatusCode().value(), response.getHeaders(), body);
    }

    private static DouyinRedirectResponse toResponse(HttpStatusCodeException response) {
        return toResponse(
                response.getStatusCode().value(),
                response.getResponseHeaders(),
                response.getResponseBodyAsByteArray());
    }

    private static DouyinRedirectResponse toResponse(
            int statusCode,
            HttpHeaders headers,
            byte[] body
    ) {
        URI location = headers == null ? null : headers.getLocation();
        String contentType = headers == null || headers.getContentType() == null
                ? null
                : headers.getContentType().toString();
        return new DouyinRedirectResponse(
                statusCode,
                location,
                contentType,
                body == null ? new byte[0] : body);
    }
}
