package top.sywyar.pixivdownload.douyin.client;

import org.springframework.http.HttpHeaders;

public final class DouyinRequestHeaders {

    public static final String REFERER = "https://www.douyin.com/?recommend=1";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    private DouyinRequestHeaders() {
    }

    public static void apply(HttpHeaders headers, String cookie) {
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.REFERER, REFERER);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        if (cookie != null && !cookie.isBlank()) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
    }
}
