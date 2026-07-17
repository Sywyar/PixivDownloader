package top.sywyar.pixivdownload.core.gallery.runtime;

import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;

import java.util.Set;

/** Stable read-only facade for consuming the host gallery runtime. */
public interface GalleryRuntimeQuery {

    /** Returns only descriptors and frontend contributions visible to the allowed data-access set. */
    GalleryRuntimeSnapshot snapshot(Set<GalleryDataAccess> allowedAccess);

    GalleryProjectionPage page(GalleryProjectionQuery query, Set<GalleryDataAccess> allowedAccess);

    GalleryCountResult count(GalleryProjectionQuery query, Set<GalleryDataAccess> allowedAccess);

    GalleryFacetPage facets(GalleryProjectionQuery query, Set<GalleryDataAccess> allowedAccess);

    GalleryWorkResult findWork(GalleryWorkKey key, Set<GalleryDataAccess> allowedAccess);
}
