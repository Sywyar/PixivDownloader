package top.sywyar.pixivdownload.core.gallery;

import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;

import java.util.List;

/** Source-owned port for list projections, counts and facets under one identical predicate. */
public interface GalleryProjectionProvider {

    String providerId();

    List<GalleryProjectionDescriptor> projections();

    GalleryProjectionPage page(GalleryProjectionQuery query);

    long count(GalleryProjectionQuery query);

    default GalleryFacetPage facets(GalleryProjectionQuery query) {
        return GalleryFacetPage.empty();
    }
}
