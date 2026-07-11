package top.sywyar.pixivdownload.gallery.frontend;

import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;
import java.util.Set;

/** Declares source-owned Pixiv card, media and detail enhancements. */
@PluginManagedBean
public final class PixivGalleryFrontendProvider implements GalleryFrontendProvider {

    public static final String MODULE_URL = "/pixiv-gallery/pixiv-gallery-frontend.js";

    private static final Set<String> SOURCES = Set.of("pixiv");
    private static final Set<String> WORK_NAMESPACES = Set.of("artwork");
    private static final Set<GalleryKind> GALLERY_KINDS = Set.of(GalleryKind.IMAGE);

    @Override
    public List<GalleryFrontendContribution> frontendContributions() {
        return List.of(
                new GalleryFrontendContribution(
                        "pixiv.card",
                        MODULE_URL,
                        scope(Set.of()),
                        Set.of(GalleryFrontendHook.CARD_EXTENSION),
                        null, null, null, null,
                        10),
                new GalleryFrontendContribution(
                        "pixiv.media",
                        MODULE_URL,
                        scope(Set.of(GalleryMediaKind.IMAGE, GalleryMediaKind.UGOIRA)),
                        Set.of(GalleryFrontendHook.MEDIA_RENDERER),
                        null, null, null, null,
                        10),
                new GalleryFrontendContribution(
                        "pixiv.detail-actions",
                        MODULE_URL,
                        scope(Set.of(GalleryMediaKind.IMAGE, GalleryMediaKind.UGOIRA)),
                        Set.of(GalleryFrontendHook.DETAIL_ACTION),
                        null, null, null, null,
                        10));
    }

    private static GalleryFrontendScope scope(Set<GalleryMediaKind> mediaKinds) {
        return new GalleryFrontendScope(SOURCES, WORK_NAMESPACES, GALLERY_KINDS, mediaKinds);
    }
}
