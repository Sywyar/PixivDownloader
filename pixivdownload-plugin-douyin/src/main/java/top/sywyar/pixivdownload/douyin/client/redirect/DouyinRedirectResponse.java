package top.sywyar.pixivdownload.douyin.client.redirect;

import java.net.URI;

public record DouyinRedirectResponse(
        int statusCode,
        URI location,
        String contentType,
        byte[] body
) {
}
