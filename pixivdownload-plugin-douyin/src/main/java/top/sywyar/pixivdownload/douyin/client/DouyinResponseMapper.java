package top.sywyar.pixivdownload.douyin.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWorkKind;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DouyinResponseMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RENDER_DATA = Pattern.compile(
            "<script[^>]+id=[\"']RENDER_DATA[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNIVERSAL_DATA = Pattern.compile(
            "window\\.__UNIVERSAL_DATA_FOR_REHYDRATION__\\s*=\\s*(\\{.*?})\\s*;</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROUTER_DATA = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?})\\s*;</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private DouyinResponseMapper() {
    }

    static DouyinWork workFromAweme(JsonNode aweme,
                                    String pageUrl,
                                    String collectionId,
                                    String collectionTitle) throws DouyinClientException {
        String id = firstText(aweme, "aweme_id", "group_id", "id");
        if (id == null || id.isBlank()) {
            throw new DouyinClientException(DouyinClientErrorCode.UNSUPPORTED_CONTENT,
                    "Douyin aweme response has no work id");
        }
        String description = firstText(aweme, "desc");
        String itemTitle = firstText(aweme, "item_title");
        String caption = firstText(aweme, "caption");
        String shareTitle = firstText(aweme.path("share_info"), "share_title", "title");
        String title = firstNonBlank(itemTitle, shareTitle, description, caption);
        JsonNode author = aweme.path("author");
        String authorId = firstText(author, "uid", "sec_uid", "short_id");
        String authorName = firstText(author, "nickname", "unique_id", "short_id");
        String canonicalUrl = pageUrl == null || pageUrl.isBlank() ? "https://www.douyin.com/video/" + id : pageUrl;
        List<DouyinMedia> media = collectMedia(id, aweme);
        if (media.isEmpty()) {
            throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                    "Douyin response has no downloadable public media URL");
        }
        DouyinMedia primary = media.get(0);
        String thumbnail = firstUrl(aweme.path("video").path("cover"))
                .or(() -> firstUrl(aweme.path("cover")))
                .orElseGet(() -> media.stream()
                        .filter(item -> item.type() == DouyinMediaType.IMAGE)
                        .map(item -> item.url().toString())
                        .findFirst()
                        .orElse(""));
        DouyinWorkKind kind = classifyWorkKind(media);
        Long publishTime = firstLong(aweme, "create_time", "createTime")
                .filter(value -> value > 0 && value <= Instant.now().plusSeconds(86_400).getEpochSecond())
                .orElse(null);
        return new DouyinWork(id, blankToDefault(title, id), description, itemTitle, caption, authorId,
                authorName == null ? "" : authorName, canonicalUrl, thumbnail, primary.url(),
                media, kind, publishTime, collectionId, collectionTitle);
    }

    private static List<DouyinMedia> collectMedia(String workId, JsonNode aweme) throws DouyinClientException {
        List<DouyinMedia> imagePostMedia = collectImagePostMedia(workId, imageNodes(aweme));
        if (!imagePostMedia.isEmpty()) {
            return imagePostMedia;
        }
        return collectVideos(workId, aweme.path("video"));
    }

    private static List<DouyinMedia> collectImagePostMedia(String workId, List<JsonNode> imageNodes)
            throws DouyinClientException {
        List<DouyinMedia> media = new ArrayList<>();
        for (int nodeIndex = 0; nodeIndex < imageNodes.size(); nodeIndex++) {
            JsonNode image = imageNodes.get(nodeIndex);
            int pageIndex = nodeIndex + 1;
            Optional<UrlCandidate> imageUrl = bestImageUrl(image);
            Optional<DouyinMedia> motion = collectLivePhotoVideo(workId, pageIndex, image);
            boolean declaresMotion = declaresLivePhotoMotion(image);
            if (declaresMotion && (imageUrl.isEmpty() || motion.isEmpty())) {
                throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                        "Douyin live photo item did not contain a complete image and motion pair");
            }
            if (imageUrl.isEmpty()) {
                continue;
            }
            media.add(media(workId + "-p" + pageIndex, DouyinMediaType.IMAGE, imageUrl.get().url(),
                    workId + "-p" + String.format(Locale.ROOT, "%02d", pageIndex), imageUrl.get().node()));
            motion.ifPresent(media::add);
        }
        return media;
    }

    private static Optional<DouyinMedia> collectLivePhotoVideo(String workId, int pageIndex, JsonNode image) {
        String id = workId + "-live-p" + pageIndex;
        String stem = workId + "-live-p" + String.format(Locale.ROOT, "%02d", pageIndex);
        Optional<DouyinMedia> nested = collectVideos(id, image.path("video")).stream().findFirst();
        if (nested.isPresent()) {
            DouyinMedia video = nested.get();
            return Optional.of(new DouyinMedia(video.id(), DouyinMediaType.LIVE_PHOTO_VIDEO, video.url(),
                    stem, video.extension(), video.sizeBytes(), video.contentType(), video.fallbackUrls()));
        }
        for (String field : List.of("video_play_addr", "video_download_addr")) {
            JsonNode address = image.path(field);
            Optional<String> url = firstUrl(address);
            if (url.isPresent()) {
                return Optional.of(media(id, DouyinMediaType.LIVE_PHOTO_VIDEO,
                        url.get(), stem, address));
            }
        }
        return Optional.empty();
    }

    private static boolean declaresLivePhotoMotion(JsonNode image) {
        JsonNode nested = image.path("video");
        if (nested.isObject() && !nested.isEmpty()) {
            return true;
        }
        for (String field : List.of("video_play_addr", "video_download_addr")) {
            JsonNode address = image.path(field);
            if (!address.isMissingNode() && !address.isNull()
                    && !(address.isObject() && address.isEmpty())) {
                return true;
            }
        }
        return false;
    }

    private static List<JsonNode> imageNodes(JsonNode aweme) {
        for (JsonNode candidate : List.of(
                aweme.path("image_post_info").path("images"),
                aweme.path("image_post_info").path("image_list"),
                aweme.path("images"),
                aweme.path("image_list"))) {
            if (candidate.isArray() && !candidate.isEmpty()) {
                List<JsonNode> nodes = new ArrayList<>(candidate.size());
                candidate.forEach(nodes::add);
                return nodes;
            }
        }
        return List.of();
    }

    private static List<DouyinMedia> collectVideos(String workId, JsonNode video) {
        if (!video.isObject()) {
            return List.of();
        }
        List<VideoCandidate> candidates = new ArrayList<>();
        JsonNode bitRate = video.path("bit_rate");
        if (bitRate.isArray()) {
            for (JsonNode item : bitRate) {
                firstUrl(item.path("play_addr")).ifPresent(url ->
                        candidates.add(new VideoCandidate(url, item.path("bit_rate").asLong(0L), item.path("play_addr"))));
            }
        }
        for (String field : List.of("play_addr", "play_addr_h264", "play_addr_265", "play_addr_256", "download_addr")) {
            firstUrl(video.path(field)).ifPresent(url ->
                    candidates.add(new VideoCandidate(url, 0L, video.path(field))));
        }
        return candidates.stream()
                .sorted(Comparator.comparingLong(VideoCandidate::quality).reversed())
                .map(candidate -> media(workId, DouyinMediaType.VIDEO, candidate.url(), workId, candidate.node()))
                .findFirst()
                .map(List::of)
                .orElse(List.of());
    }

    private static DouyinMedia media(String id, DouyinMediaType type, String rawUrl, String stem, JsonNode node) {
        URI uri = URI.create(rawUrl);
        String extension = extensionFromUrl(uri).orElse(type == DouyinMediaType.IMAGE ? "jpg" : "mp4");
        Long size = firstLong(node, "data_size", "file_size", "size", "content_length").orElse(null);
        List<URI> fallbackUrls = allUrls(node).stream()
                .filter(candidate -> !candidate.equals(rawUrl))
                .map(DouyinResponseMapper::safeUri)
                .flatMap(Optional::stream)
                .toList();
        return new DouyinMedia(id, type, uri, stem, extension, size, null, fallbackUrls);
    }

    private static DouyinWorkKind classifyWorkKind(List<DouyinMedia> media) {
        boolean hasImage = media.stream().anyMatch(item -> item.type() == DouyinMediaType.IMAGE);
        boolean hasLiveVideo = media.stream().anyMatch(item -> item.type() == DouyinMediaType.LIVE_PHOTO_VIDEO);
        if (hasImage && hasLiveVideo) {
            return DouyinWorkKind.LIVE_PHOTO;
        }
        if (hasImage) {
            return DouyinWorkKind.IMAGE_NOTE;
        }
        return media.isEmpty() ? DouyinWorkKind.UNSUPPORTED : DouyinWorkKind.VIDEO;
    }

    private static Optional<UrlCandidate> bestImageUrl(JsonNode image) {
        for (String field : List.of(
                "watermark_free_download_url_list",
                "url_list",
                "origin_image",
                "display_image",
                "download_url",
                "download_addr",
                "download_url_list",
                "owner_watermark_image")) {
            JsonNode candidate = image.path(field);
            Optional<String> found = field.endsWith("_list")
                    ? firstUrlFromArray(candidate)
                    : firstUrl(candidate);
            if (found.isPresent()) {
                return Optional.of(new UrlCandidate(found.get(), candidate));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isTextual() && node.asText().startsWith("http")) {
            return Optional.of(node.asText());
        }
        Optional<String> direct = firstUrlFromArray(node.path("url_list"));
        if (direct.isPresent()) {
            return direct;
        }
        for (String field : List.of("uri", "url", "download_url")) {
            JsonNode value = node.path(field);
            if (value.isTextual() && value.asText().startsWith("http")) {
                return Optional.of(value.asText());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUrlFromArray(JsonNode array) {
        if (!array.isArray()) {
            return Optional.empty();
        }
        for (JsonNode item : array) {
            if (item.isTextual() && item.asText().startsWith("http")) {
                return Optional.of(item.asText());
            }
        }
        return Optional.empty();
    }

    private static List<String> allUrls(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (node.isTextual() && node.asText().startsWith("http")) {
            urls.add(node.asText());
        }
        JsonNode list = node.isArray() ? node : node.path("url_list");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if (item.isTextual() && item.asText().startsWith("http")) {
                    urls.add(item.asText());
                }
            }
        }
        for (String field : List.of("uri", "url", "download_url")) {
            JsonNode value = node.path(field);
            if (value.isTextual() && value.asText().startsWith("http")) {
                urls.add(value.asText());
            }
        }
        return List.copyOf(urls);
    }

    private static Optional<URI> safeUri(String rawUrl) {
        try {
            return Optional.of(URI.create(rawUrl));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static List<JsonNode> extractPageJson(String html) {
        List<JsonNode> nodes = new ArrayList<>();
        addScriptJson(nodes, RENDER_DATA.matcher(html), true);
        addScriptJson(nodes, UNIVERSAL_DATA.matcher(html), false);
        addScriptJson(nodes, ROUTER_DATA.matcher(html), false);
        return nodes;
    }

    private static void addScriptJson(List<JsonNode> nodes, Matcher matcher, boolean urlEncoded) {
        while (matcher.find()) {
            String json = htmlUnescape(matcher.group(1).trim());
            if (urlEncoded) {
                json = URLDecoder.decode(json, StandardCharsets.UTF_8);
            }
            try {
                nodes.add(MAPPER.readTree(json));
            } catch (JsonProcessingException ignored) {
                // 忽略单个损坏的 hydration 数据块，继续扫描页面中的其它数据。
            }
        }
    }

    static Optional<JsonNode> findFirstField(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject() && node.has(field)) {
            return Optional.of(node.get(field));
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                Optional<JsonNode> found = findFirstField(child, field);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    static Optional<JsonNode> findAwemeById(JsonNode node, String id) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject()) {
            String awemeId = firstText(node, "aweme_id", "group_id", "id");
            if (id.equals(awemeId) && (node.has("video") || node.has("image_post_info") || node.has("images"))) {
                return Optional.of(node);
            }
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                Optional<JsonNode> found = findAwemeById(child, id);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    static Optional<JsonNode> firstObject(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode found = root.path(field);
            if (found.isObject()) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> firstArray(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode found = root.path(field);
            if (found.isArray()) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    static JsonNode requireRecognizedArray(
            JsonNode root,
            String responseName,
            String... fields) throws DouyinClientException {
        Optional<JsonNode> array = firstArray(root, fields);
        if (array.isPresent()) {
            return array.get();
        }
        if (root != null) {
            for (String field : fields) {
                if (root.has(field) && root.get(field).isNull()) {
                    return MAPPER.createArrayNode();
                }
            }
        }
        throw new DouyinClientException(
                DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                responseName + " did not contain a recognized result array");
    }

    static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if ((value.isTextual() || value.isNumber()) && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static Optional<Long> firstLong(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
                return Optional.of(value.asLong());
            }
            if (value.isTextual() && value.asText().matches("\\d+")) {
                return Optional.of(Long.parseLong(value.asText()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extensionFromUrl(URI uri) {
        String path = uri == null ? "" : uri.getPath();
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return Optional.empty();
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? Optional.of(ext) : Optional.empty();
    }

    static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String htmlUnescape(String value) {
        return value.replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private record VideoCandidate(String url, long quality, JsonNode node) {
    }

    private record UrlCandidate(String url, JsonNode node) {
    }
}
