package top.sywyar.pixivdownload.core.gallery.frontend;

import java.util.List;

/** 画廊前端贡献来源端口；发现与装配机制由宿主运行时决定。 */
@FunctionalInterface
public interface GalleryFrontendProvider {

    List<GalleryFrontendContribution> frontendContributions();
}
