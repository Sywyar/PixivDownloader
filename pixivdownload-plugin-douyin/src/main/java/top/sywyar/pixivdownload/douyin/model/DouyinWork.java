package top.sywyar.pixivdownload.douyin.model;

import java.net.URI;
import java.util.List;

public record DouyinWork(
        String id,
        String title,
        String description,
        String itemTitle,
        String caption,
        String authorId,
        String authorName,
        String pageUrl,
        String thumbnailUrl,
        URI mediaUrl,
        List<DouyinMedia> media,
        DouyinWorkKind kind,
        Long publishTimeEpochSeconds,
        String collectionId,
        String collectionTitle
) {

    public DouyinWork {
        media = media == null ? List.of() : List.copyOf(media);
        if (kind == null) {
            kind = media.isEmpty() ? DouyinWorkKind.UNSUPPORTED : DouyinWorkKind.VIDEO;
        }
    }

    public DouyinWork(String id,
                      String title,
                      String authorId,
                      String authorName,
                      String pageUrl,
                      String thumbnailUrl,
                      URI mediaUrl,
                      List<DouyinMedia> media,
                      DouyinWorkKind kind,
                      Long publishTimeEpochSeconds,
                      String collectionId,
                      String collectionTitle) {
        this(id, title, null, null, null, authorId, authorName, pageUrl, thumbnailUrl, mediaUrl,
                media, kind, publishTimeEpochSeconds, collectionId, collectionTitle);
    }

    public DouyinWork(String id,
                      String title,
                      String authorId,
                      String authorName,
                      String pageUrl,
                      String thumbnailUrl,
                      URI mediaUrl) {
        this(id, title, null, null, null, authorId, authorName, pageUrl, thumbnailUrl, mediaUrl,
                mediaUrl == null
                        ? List.of()
                        : List.of(new DouyinMedia(id, DouyinMediaType.VIDEO, mediaUrl, id, "mp4", null, null)),
                mediaUrl == null ? DouyinWorkKind.UNSUPPORTED : DouyinWorkKind.VIDEO,
                null, null, null);
    }
}
