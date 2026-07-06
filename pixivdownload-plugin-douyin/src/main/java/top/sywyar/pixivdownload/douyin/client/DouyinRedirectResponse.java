package top.sywyar.pixivdownload.douyin.client;

import java.net.URI;

public record DouyinRedirectResponse(
        int statusCode,
        URI location,
        String contentType,
        byte[] body
) {
}
