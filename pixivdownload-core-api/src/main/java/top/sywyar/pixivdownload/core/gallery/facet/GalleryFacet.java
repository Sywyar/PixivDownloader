package top.sywyar.pixivdownload.core.gallery.facet;

public sealed interface GalleryFacet permits GalleryAuthorFacet, GalleryTagFacet {

    String sourceId();

    GalleryFacetType type();

    long count();
}
