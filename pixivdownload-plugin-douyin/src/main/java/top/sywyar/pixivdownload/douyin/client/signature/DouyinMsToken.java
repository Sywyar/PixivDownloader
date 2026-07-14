package top.sywyar.pixivdownload.douyin.client.signature;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;

public final class DouyinMsToken {

    private static final Pattern COOKIE_PART = Pattern.compile("\\s*;\\s*");
    private static final String TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private DouyinMsToken() {
    }

    public static String ensure(String cookie) {
        return fromCookie(cookie).orElseGet(DouyinMsToken::fallback);
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

    static String fallback() {
        StringBuilder token = new StringBuilder(184);
        for (int i = 0; i < 182; i++) {
            token.append(TOKEN_CHARS.charAt(RANDOM.nextInt(TOKEN_CHARS.length())));
        }
        token.append("==");
        return token.toString();
    }
}
