package top.sywyar.pixivdownload.novel;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NovelCoverUrlResolver {

    private static final Pattern CACHED_NOVEL_COVER_PATH =
            Pattern.compile("^/c/[^/]+/(novel-cover-(?:master|original)/.+)$");

    private NovelCoverUrlResolver() {
    }

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
