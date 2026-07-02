package top.sywyar.pixivdownload.core.pixiv;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class PixivAjaxProxyClient {

    private final RestTemplate restTemplate;

    public String proxyGet(String url, String cookie) {
        return proxyGetUri(URI.create(url), cookie);
    }

    public String proxyGetUri(URI uri, String cookie) {
        HttpEntity<Void> entity = new HttpEntity<>(PixivRequestHeaders.ajax(cookie));
        ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, entity, byte[].class);
        byte[] body = response.getBody();
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }
}
