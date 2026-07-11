package top.sywyar.pixivdownload.novelgallery.frontend;

import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;
import java.util.Set;

/** Declares the novel plugin's neutral media and detail enhancements. */
@PluginManagedBean
public final class NovelGalleryFrontendProvider implements GalleryFrontendProvider {

    public static final String MODULE_URL = "/pixiv-novel-gallery/novel-gallery-frontend.js";

    private static final Set<String> SOURCES = Set.of("pixiv");
    private static final Set<String> WORK_NAMESPACES = Set.of("novel");
    private static final Set<GalleryKind> GALLERY_KINDS = Set.of(GalleryKind.NOVEL);

    @Override
    public List<GalleryFrontendContribution> frontendContributions() {
        return List.of(
                new GalleryFrontendContribution(
                        "novel.text-renderer",
                        MODULE_URL,
                        scope(Set.of(GalleryMediaKind.TEXT)),
                        Set.of(GalleryFrontendHook.MEDIA_RENDERER),
                        null, null, null, null,
                        40),
                new GalleryFrontendContribution(
                        "novel.detail-actions",
                        MODULE_URL,
                        scope(Set.of(GalleryMediaKind.TEXT, GalleryMediaKind.COVER)),
                        Set.of(GalleryFrontendHook.DETAIL_ACTION),
                        null, null, null, null,
                        40));
    }

    private static GalleryFrontendScope scope(Set<GalleryMediaKind> mediaKinds) {
        return new GalleryFrontendScope(SOURCES, WORK_NAMESPACES, GALLERY_KINDS, mediaKinds);
    }
}
