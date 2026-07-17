package top.sywyar.pixivdownload.core.pixiv;

public final class PixivCookieUserResolver {

    private PixivCookieUserResolver() {
    }

    /**
     * 从 Pixiv cookie 串里抽出登录用户的 userId。
     * <p>PHPSESSID 格式为 {@code {userId}_{随机后缀}}，下划线前缀即 userId。返回 null 表示
     * cookie 缺失或不含合法 PHPSESSID（未登录 / 已过期 / 拼装错误）。
     */
    public static String extractUidFromCookie(String cookie) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.regionMatches(true, 0, "PHPSESSID=", 0, "PHPSESSID=".length())) continue;
            String value = trimmed.substring("PHPSESSID=".length());
            int us = value.indexOf('_');
            if (us <= 0) continue;
            String uid = value.substring(0, us);
            if (!uid.isEmpty() && uid.chars().allMatch(Character::isDigit)) return uid;
        }
        return null;
    }
}
