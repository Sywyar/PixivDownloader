package top.sywyar.pixivdownload.core.gallery;

import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;

import java.util.List;
import java.util.Optional;

/** Source-owned port for complete work details independent of the selected list projection. */
public interface GalleryWorkProvider {

    String providerId();

    List<GalleryWorkDescriptor> works();

    Optional<GalleryWork> find(GalleryWorkKey key);
}
