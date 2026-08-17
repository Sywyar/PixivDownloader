package top.sywyar.pixivdownload.douyin.gallery;

import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Source-owned response assembly for the independent Douyin gallery. */
@PluginManagedBean
public class DouyinGalleryDataProvider {

    public static final String SOURCE_ID = "douyin";
    public static final String WORK_NAMESPACE = "aweme";

    private final DouyinHistoryService historyService;

    public DouyinGalleryDataProvider(DouyinHistoryService historyService) {
        this.historyService = historyService;
    }

    public Projection projection(DouyinWorkRecord work, Kind kind) {
        WorkKey workKey = new WorkKey(SOURCE_ID, WORK_NAMESPACE, work.workId());
        List<DouyinWorkFileRecord> files = historyService.findFilesByWorkId(work.workId());
        Set<MediaKind> mediaKinds = new LinkedHashSet<>();
        files.stream().map(DouyinGalleryDataProvider::mediaKind).forEach(mediaKinds::add);
        String preferredMediaId = files.stream()
                .filter(file -> projectionContains(kind, mediaKind(file)))
                .findFirst().map(DouyinGalleryDataProvider::mediaId).orElse(null);
        String thumbnailUrl = files.stream()
                .filter(file -> mediaKind(file) == MediaKind.IMAGE || mediaKind(file) == MediaKind.COVER)
                .findFirst().map(file -> mediaUrl(work.workId(), file.fileIndex())).orElse(null);
        return new Projection(
                new ProjectionKey(workKey, kind),
                firstNonBlank(work.title(), work.itemTitle(), work.caption(), work.workId()),
                firstNonBlank(work.description(), work.caption()),
                thumbnailUrl,
                actor(work),
                List.of(),
                instant(work.publishTime()),
                instant(work.time()),
                instant(work.time()),
                mediaKinds,
                "SFW",
                "UNKNOWN",
                preferredMediaId,
                attributes(work));
    }

    public Kind primaryKind(DouyinWorkRecord work) {
        return historyService.findFilesByWorkId(work.workId()).stream()
                .map(DouyinGalleryDataProvider::mediaKind)
                .anyMatch(kind -> kind == MediaKind.IMAGE)
                ? Kind.IMAGE : Kind.VIDEO;
    }

    public Optional<Work> find(String workId) {
        if (workId == null || workId.isBlank()) {
            return Optional.empty();
        }
        return historyService.findById(workId.trim()).map(this::toWork);
    }

    private Work toWork(DouyinWorkRecord work) {
        WorkKey key = new WorkKey(SOURCE_ID, WORK_NAMESPACE, work.workId());
        List<MediaAsset> media = historyService.findFilesByWorkId(work.workId()).stream()
                .map(file -> toMedia(key, file))
                .toList();
        return new Work(
                key,
                firstNonBlank(work.title(), work.itemTitle(), work.caption(), work.workId()),
                firstNonBlank(work.description(), work.caption()),
                actor(work),
                List.of(),
                instant(work.publishTime()),
                instant(work.time()),
                instant(work.time()),
                "SFW",
                "UNKNOWN",
                media,
                attributes(work));
    }

    private static MediaAsset toMedia(WorkKey workKey, DouyinWorkFileRecord file) {
        String id = mediaId(file);
        String url = mediaUrl(workKey.sourceWorkId(), file.fileIndex());
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "fileName", file.fileName());
        put(attributes, "extension", file.extension());
        put(attributes, "bytes", file.bytes());
        put(attributes, "fileIndex", file.fileIndex());
        MediaKind kind = mediaKind(file);
        return new MediaAsset(new MediaKey(workKey, id), kind, url,
                kind == MediaKind.COVER ? url : null, file.contentType(), attributes);
    }

    private static String mediaUrl(String workId, int fileIndex) {
        return "/api/douyin/history/" + workId + "/media/" + fileIndex;
    }

    private static Actor actor(DouyinWorkRecord work) {
        return isBlank(work.authorId()) ? null
                : new Actor(SOURCE_ID, work.authorId(), work.authorName(), null);
    }

    private static Map<String, String> attributes(DouyinWorkRecord work) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "sourceUrl", work.sourceUrl());
        put(out, "canonicalUrl", work.canonicalUrl());
        put(out, "collectionId", work.collectionId());
        put(out, "collectionTitle", work.collectionTitle());
        put(out, "collectionOrder", work.collectionOrder());
        put(out, "fileCount", work.count());
        return out;
    }

    private static MediaKind mediaKind(DouyinWorkFileRecord file) {
        if (file.mediaType() == null) {
            return MediaKind.UNKNOWN;
        }
        try {
            return MediaKind.valueOf(file.mediaType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MediaKind.UNKNOWN;
        }
    }

    private static String mediaId(DouyinWorkFileRecord file) {
        return isBlank(file.mediaId()) ? "index-" + file.fileIndex() : file.mediaId().trim();
    }

    private static boolean projectionContains(Kind kind, MediaKind mediaKind) {
        return kind == Kind.IMAGE && mediaKind == MediaKind.IMAGE
                || kind == Kind.VIDEO
                && (mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.LIVE_PHOTO_VIDEO);
    }

    private static Instant instant(Long millis) {
        return millis == null || millis <= 0 ? null : Instant.ofEpochMilli(millis);
    }

    private static Instant instant(long millis) {
        return millis <= 0 ? null : Instant.ofEpochMilli(millis);
    }

    private static void put(Map<String, String> out, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.put(key, String.valueOf(value));
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Kind { IMAGE, VIDEO }

    public enum MediaKind { IMAGE, VIDEO, LIVE_PHOTO_VIDEO, COVER, UNKNOWN }

    public record WorkKey(String sourceId, String sourceWorkNamespace, String sourceWorkId) { }

    public record ProjectionKey(WorkKey workKey, Kind kind) { }

    public record Actor(String sourceId, String actorId, String name, String avatarUrl) { }

    public record Projection(
            ProjectionKey key,
            String title,
            String summary,
            String thumbnailUrl,
            Actor author,
            List<Object> tags,
            Instant createdAt,
            Instant downloadedAt,
            Instant updatedAt,
            Set<MediaKind> containedMediaKinds,
            String contentRating,
            String aiStatus,
            String preferredMediaId,
            Map<String, String> attributes
    ) { }

    public record MediaKey(WorkKey workKey, String mediaId) { }

    public record MediaAsset(
            MediaKey key,
            MediaKind kind,
            String url,
            String thumbnailUrl,
            String mimeType,
            Map<String, String> attributes
    ) { }

    public record Work(
            WorkKey key,
            String title,
            String description,
            Actor author,
            List<Object> tags,
            Instant createdAt,
            Instant downloadedAt,
            Instant updatedAt,
            String contentRating,
            String aiStatus,
            List<MediaAsset> media,
            Map<String, String> attributes
    ) { }
}
