package top.sywyar.pixivdownload.douyin.client;

import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class DouyinRequestHeaders {

    public static final String REFERER = "https://www.douyin.com/?recommend=1";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    private DouyinRequestHeaders() {
    }

    private static final Set<String> CREDENTIAL_HOSTS = Set.of(
            "douyin.com", "www.douyin.com", "v.douyin.com",
            "iesdouyin.com", "www.iesdouyin.com", "v.iesdouyin.com");

    public static void applyStandard(HttpHeaders headers) {
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.REFERER, REFERER);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
    }

    public static void applyCredentials(HttpHeaders headers, URI target, String cookie)
            throws DouyinClientException {
        applyStandard(headers);
        if (cookie == null || cookie.isBlank()) {
            return;
        }
        if (!isCredentialOrigin(target)) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL,
                    "Douyin credentials are not allowed for target origin: host=" + safeHost(target));
        }
        headers.set(HttpHeaders.COOKIE, cookie);
    }

    public static boolean isCredentialOrigin(URI target) {
        if (target == null || !"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null) {
            return false;
        }
        int port = target.getPort();
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        return (port == -1 || port == 443) && CREDENTIAL_HOSTS.contains(host);
    }

    private static String safeHost(URI target) {
        return target == null || target.getHost() == null ? "<none>" : target.getHost().toLowerCase(Locale.ROOT);
    }
}
