package top.sywyar.pixivdownload.douyin.parse;

import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DouyinUrlParser {

    private static final Pattern FIRST_URL = Pattern.compile("https?://[^\\s<>\"'，。；、,;]+");
    private static final Pattern SHORT_HOST_PREFIX =
            Pattern.compile("^(v\\.douyin\\.com|v\\.iesdouyin\\.com|iesdouyin\\.com)(/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_PATH = Pattern.compile("^/video/([^/?#]+)");
    private static final Pattern NOTE_PATH = Pattern.compile("^/note/([^/?#]+)");
    private static final Pattern GALLERY_PATH = Pattern.compile("^/(?:gallery|slides)/([^/?#]+)");
    private static final Pattern SHARE_WORK_PATH = Pattern.compile("^/share/(?:video|note|gallery|slides)/([^/?#]+)");
    private static final Pattern USER_PATH = Pattern.compile("^/user/([^/?#]+)");
    private static final Pattern COLLECTION_PATH = Pattern.compile("^/(?:collection|mix)/([^/?#]+)");
    private static final Pattern MUSIC_PATH = Pattern.compile("^/music/([^/?#]+)");
    private static final Pattern QUEUE_SHORT_ID = Pattern.compile("^d?short-([A-Za-z0-9_-]+)$");
    private static final Pattern QUEUE_VIDEO_ID = Pattern.compile("^d(\\d{5,})$");
    private static final Pattern PLAIN_WORK_ID = Pattern.compile("^(\\d{5,})$");

    public Optional<DouyinParsedInput> parse(String input) {
        String original = input == null ? "" : input.trim();
        if (original.isEmpty()) {
            return Optional.empty();
        }
        Optional<DouyinParsedInput> queued = parseQueueId(original);
        if (queued.isPresent()) {
            return queued;
        }
        String url = normalizePossibleBareUrl(extractFirstUrl(original).orElse(original));
        URI uri = parseUri(stripTrailingPunctuation(url)).orElse(null);
        if (uri == null || uri.getHost() == null || !isDouyinHost(uri.getHost())) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String canonicalUrl = uri.toString();
        if (isShortHost(host)) {
            String code = firstPathSegment(path).orElse(null);
            if (code == null) {
                return Optional.empty();
            }
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.SHORT_LINK, original, uri.toString(), code, canonicalUrl));
        }
        String modalId = queryParam(uri.getRawQuery(), "modal_id").orElse(null);
        if (modalId != null && modalId.matches("\\d{5,}")) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.VIDEO, original, uri.toString(), modalId,
                    "https://www.douyin.com/video/" + modalId));
        }
        Matcher video = VIDEO_PATH.matcher(path);
        if (video.find()) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.VIDEO, original, uri.toString(), video.group(1), canonicalUrl));
        }
        Matcher note = NOTE_PATH.matcher(path);
        if (note.find()) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.NOTE, original, uri.toString(), note.group(1), canonicalUrl));
        }
        Matcher gallery = GALLERY_PATH.matcher(path);
        if (gallery.find()) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.GALLERY, original, uri.toString(), gallery.group(1), canonicalUrl));
        }
        Matcher shareWork = SHARE_WORK_PATH.matcher(path);
        if (shareWork.find()) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.VIDEO, original, uri.toString(), shareWork.group(1), canonicalUrl));
        }
        Matcher user = USER_PATH.matcher(path);
        if (user.find()) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.USER_PROFILE, original, uri.toString(), user.group(1), canonicalUrl));
        }
        Matcher collection = COLLECTION_PATH.matcher(path);
        if (collection.find()) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.COLLECTION, original, uri.toString(), collection.group(1), canonicalUrl));
        }
        Matcher music = MUSIC_PATH.matcher(path);
        if (music.find()) {
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.MUSIC, original, uri.toString(), music.group(1), canonicalUrl));
        }
        return Optional.empty();
    }

    private static Optional<DouyinParsedInput> parseQueueId(String original) {
        Matcher shortId = QUEUE_SHORT_ID.matcher(original);
        if (shortId.matches()) {
            String code = shortId.group(1);
            String url = "https://v.douyin.com/" + code + "/";
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.SHORT_LINK, original, url, code, url));
        }
        Matcher videoId = QUEUE_VIDEO_ID.matcher(original);
        if (videoId.matches()) {
            String id = videoId.group(1);
            String url = "https://www.douyin.com/video/" + id;
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.VIDEO, original, url, id, url));
        }
        Matcher plainWorkId = PLAIN_WORK_ID.matcher(original);
        if (plainWorkId.matches()) {
            String id = plainWorkId.group(1);
            String url = "https://www.douyin.com/video/" + id;
            return Optional.of(new DouyinParsedInput(
                    DouyinParsedKind.VIDEO, original, url, id, url));
        }
        return Optional.empty();
    }

    public Optional<String> extractFirstUrl(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = FIRST_URL.matcher(text);
        return matcher.find() ? Optional.of(stripTrailingPunctuation(matcher.group())) : Optional.empty();
    }

    private static Optional<URI> parseUri(String raw) {
        try {
            return Optional.of(new URI(raw));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static boolean isDouyinHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("douyin.com")
                || normalized.endsWith(".douyin.com")
                || normalized.equals("iesdouyin.com")
                || normalized.endsWith(".iesdouyin.com");
    }

    public static boolean isShortHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return normalized.equals("v.douyin.com")
                || normalized.equals("v.iesdouyin.com")
                || normalized.equals("iesdouyin.com");
    }

    private static Optional<String> firstPathSegment(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }

    private static String stripTrailingPunctuation(String value) {
        String result = value == null ? "" : value.trim();
        while (!result.isEmpty()) {
            char ch = result.charAt(result.length() - 1);
            if (ch == ')' || ch == ']' || ch == '}' || ch == ',' || ch == '.' || ch == ';'
                    || ch == '，' || ch == '。' || ch == '；') {
                result = result.substring(0, result.length() - 1);
                continue;
            }
            break;
        }
        return result;
    }

    private static String normalizePossibleBareUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return SHORT_HOST_PREFIX.matcher(trimmed).matches() ? "https://" + trimmed : trimmed;
    }

    private static Optional<String> queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Optional.empty();
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String rawName = equals >= 0 ? part.substring(0, equals) : part;
            String rawValue = equals >= 0 ? part.substring(equals + 1) : "";
            String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return Optional.of(URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }
}
