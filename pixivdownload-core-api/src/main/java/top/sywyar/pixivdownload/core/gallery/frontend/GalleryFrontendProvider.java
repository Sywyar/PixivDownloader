package top.sywyar.pixivdownload.core.gallery.frontend;

import java.util.List;

/** Source-plugin port discovered from an external plugin child context. */
@FunctionalInterface
public interface GalleryFrontendProvider {

    List<GalleryFrontendContribution> frontendContributions();
}
