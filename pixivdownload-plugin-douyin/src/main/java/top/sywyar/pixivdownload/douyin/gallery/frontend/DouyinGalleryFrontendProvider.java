package top.sywyar.pixivdownload.douyin.gallery.frontend;

import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;
import java.util.Set;

/** Declares source-owned card/media enhancements for neutral gallery consumers. */
@PluginManagedBean
public final class DouyinGalleryFrontendProvider implements GalleryFrontendProvider {

    public static final String MODULE_URL = "/pixiv-douyin-download/douyin-gallery-frontend.js";

    private static final Set<String> SOURCES = Set.of("douyin");
    private static final Set<String> WORK_NAMESPACES = Set.of("aweme");

    @Override
    public List<GalleryFrontendContribution> frontendContributions() {
        return List.of(
                new GalleryFrontendContribution(
                        "douyin.card",
                        MODULE_URL,
                        scope(Set.of(GalleryKind.IMAGE, GalleryKind.VIDEO), Set.of()),
                        Set.of(GalleryFrontendHook.CARD_EXTENSION),
                        null, null, null, null,
                        30),
                new GalleryFrontendContribution(
                        "douyin.media",
                        MODULE_URL,
                        scope(
                                Set.of(GalleryKind.IMAGE, GalleryKind.VIDEO),
                                Set.of(
                                        GalleryMediaKind.IMAGE,
                                        GalleryMediaKind.VIDEO,
                                        GalleryMediaKind.LIVE_PHOTO_VIDEO,
                                        GalleryMediaKind.COVER)),
                        Set.of(GalleryFrontendHook.MEDIA_RENDERER),
                        null, null, null, null,
                        30));
    }

    private static GalleryFrontendScope scope(Set<GalleryKind> galleryKinds,
                                              Set<GalleryMediaKind> mediaKinds) {
        return new GalleryFrontendScope(SOURCES, WORK_NAMESPACES, galleryKinds, mediaKinds);
    }
}
