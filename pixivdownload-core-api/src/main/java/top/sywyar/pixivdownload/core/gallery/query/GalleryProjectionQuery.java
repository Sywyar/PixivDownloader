package top.sywyar.pixivdownload.core.gallery.query;

import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;

import java.util.List;
import java.util.Objects;

/** Cursor query passed unchanged to one source projection provider. */
public record GalleryProjectionQuery(
        GalleryKind kind,
        String sourceId,
        List<GalleryFilter> filters,
        GallerySortField sortField,
        GallerySortDirection sortDirection,
        String cursor,
        int limit
) {

    public GalleryProjectionQuery {
        Objects.requireNonNull(kind, "kind");
        sourceId = sourceId == null || sourceId.isBlank() ? null : sourceId.trim();
        filters = filters == null ? List.of() : List.copyOf(filters);
        sortField = sortField == null ? GallerySortField.DOWNLOADED_AT : sortField;
        sortDirection = sortDirection == null ? GallerySortDirection.DESC : sortDirection;
        cursor = cursor == null || cursor.isBlank() ? null : cursor.trim();
        limit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
    }
}
