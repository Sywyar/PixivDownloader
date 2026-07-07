package top.sywyar.pixivdownload.core.gallery;

import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryQuery;
import top.sywyar.pixivdownload.core.gallery.model.GallerySourceDescriptor;

import java.util.List;

/**
 * Neutral gallery data port. Implementations are ordinary runtime beans owned by their plugin or core feature.
 */
public interface GalleryDataProvider {

    /** Stable provider id. The app registry validates uniqueness and syntax. */
    String providerId();

    /** Declares the sources and gallery kinds this provider can serve. */
    List<GallerySourceDescriptor> sources();

    /** Queries works using only neutral gallery query fields. */
    GalleryPage query(GalleryQuery query);

    /** Lists facets using only neutral gallery query fields. */
    default GalleryFacetPage facets(GalleryQuery query) {
        return GalleryFacetPage.empty();
    }
}
