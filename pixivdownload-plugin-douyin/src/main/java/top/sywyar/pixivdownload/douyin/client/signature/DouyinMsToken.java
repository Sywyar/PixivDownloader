package top.sywyar.pixivdownload.douyin.client.signature;

import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * 透传用户登录态里已有的抖音 msToken。
 *
 * <p>msToken 是抖音服务端下发的请求级凭据，本示例只负责把它从用户 Cookie 原样带回到请求查询与请求 Cookie，
 * 不生成、不伪造任何令牌。缺 msToken 时请求照常发出（不注入该参数），由服务端按其策略处理。</p>
 */
public final class DouyinMsToken {

    private static final Pattern COOKIE_PART = Pattern.compile("\\s*;\\s*");

    private DouyinMsToken() {
    }

    /** 返回用户 Cookie 里的 msToken；没有则返回空串（不伪造）。 */
    public static String ensure(String cookie) {
        return fromCookie(cookie).orElse("");
    }

    static String withToken(String cookie, String token) {
        StringJoiner current = new StringJoiner("; ");
        boolean tokenAdded = false;
        if (cookie != null && !cookie.isBlank()) {
            for (String part : COOKIE_PART.split(cookie.trim())) {
                String normalized = part.trim();
                int equals = normalized.indexOf('=');
                String name = equals <= 0
                        ? ""
                        : normalized.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                if ("mstoken".equals(name)) {
                    if (!tokenAdded) {
                        current.add("msToken=" + token);
                        tokenAdded = true;
                    }
                } else if (!normalized.isBlank()) {
                    current.add(normalized);
                }
            }
        }
        if (!tokenAdded) {
            current.add("msToken=" + token);
        }
        return current.toString();
    }

    public static Optional<String> fromCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return Optional.empty();
        }
        for (String part : COOKIE_PART.split(cookie.trim())) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if ("mstoken".equals(name)) {
                String value = part.substring(equals + 1).trim();
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }
}
