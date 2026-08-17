package top.sywyar.pixivdownload.core.pixiv;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Pixiv 封面地址，并优先返回可用的高分辨率候选地址。
 */
public final class PixivCoverUrlResolver {

    private static final Pattern CACHED_NOVEL_COVER_PATH =
            Pattern.compile("^/c/[^/]+/(novel-cover-(?:master|original)/.+)$");

    private PixivCoverUrlResolver() {
    }

    /**
     * 将受支持的缓存小说封面地址转换为高分辨率地址。
     *
     * @param url 原始封面地址
     * @return 高分辨率地址；无法转换时返回规范化后的原地址，空输入返回空字符串
     */
    public static String preferHighResolution(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return trimmed;
            }
            String path = uri.getRawPath();
            if (path == null) {
                return trimmed;
            }
            Matcher matcher = CACHED_NOVEL_COVER_PATH.matcher(path);
            if (!matcher.matches()) {
                return trimmed;
            }
            StringBuilder rebuilt = new StringBuilder()
                    .append(uri.getScheme())
                    .append("://")
                    .append(uri.getRawAuthority())
                    .append('/')
                    .append(matcher.group(1));
            if (uri.getRawQuery() != null) {
                rebuilt.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                rebuilt.append('#').append(uri.getRawFragment());
            }
            return rebuilt.toString();
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    /**
     * 返回按优先级排列的封面下载候选地址。
     *
     * @param url 原始封面地址
     * @return 不重复的候选地址列表，高分辨率地址优先
     */
    public static List<String> downloadCandidates(String url) {
        String highResolution = preferHighResolution(url);
        if (highResolution.isBlank()) {
            return List.of();
        }
        String original = url == null ? "" : url.trim();
        if (!original.isBlank() && !highResolution.equals(original)) {
            return List.of(highResolution, original);
        }
        return List.of(highResolution);
    }
}
