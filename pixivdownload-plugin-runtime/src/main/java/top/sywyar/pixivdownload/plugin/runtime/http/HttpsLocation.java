package top.sywyar.pixivdownload.plugin.runtime.http;

import java.net.URI;
import java.net.URISyntaxException;

/** 仓库地址的共享语法检查；DNS、地址范围及每次重定向仍由实际下载器核对。 */
public final class HttpsLocation {
    public static final int MAX_URL_CHARS = 2048;
    private HttpsLocation() { }

    public static URI parse(String text, boolean noQueryOrFragment) throws URISyntaxException {
        if (text == null || text.isBlank() || !text.equals(text.trim()) || text.length() > MAX_URL_CHARS
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new URISyntaxException(String.valueOf(text), "invalid HTTPS URL text");
        }
        URI uri = new URI(text);
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || noQueryOrFragment && (uri.getRawQuery() != null || uri.getRawFragment() != null)) {
            throw new URISyntaxException(text, "absolute public HTTPS URL required");
        }
        return uri;
    }
}
