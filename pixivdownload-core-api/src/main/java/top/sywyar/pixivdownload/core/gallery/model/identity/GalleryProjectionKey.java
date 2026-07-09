package top.sywyar.pixivdownload.core.gallery.model.identity;

import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;

import java.util.Objects;

/** Identity of one list view of a work; a work may have multiple media projections. */
public record GalleryProjectionKey(GalleryWorkKey workKey, GalleryKind kind) {

    public GalleryProjectionKey {
        Objects.requireNonNull(workKey, "workKey");
        Objects.requireNonNull(kind, "kind");
    }
}
