package top.sywyar.pixivdownload.core.gallery.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("画廊 descriptor 数据访问契约")
class GalleryDescriptorAccessTest {

    @Test
    @DisplayName("投影 descriptor 必须显式声明数据访问级别")
    void projectionDescriptorRequiresExplicitDataAccess() {
        GalleryProjectionDescriptor shared = new GalleryProjectionDescriptor(
                "pixiv", GalleryKind.IMAGE, "gallery", "source.pixiv", 10,
                GalleryDataAccess.SHARED, Map.of());

        assertThat(shared.dataAccess()).isEqualTo(GalleryDataAccess.SHARED);
        assertThatNullPointerException().isThrownBy(() -> new GalleryProjectionDescriptor(
                        "pixiv", GalleryKind.IMAGE, "gallery", "source.pixiv", 10,
                        null, Map.of()))
                .withMessage("dataAccess");
    }

    @Test
    @DisplayName("作品 descriptor 必须显式声明数据访问级别")
    void workDescriptorRequiresExplicitDataAccess() {
        GalleryWorkDescriptor adminOnly = new GalleryWorkDescriptor(
                "douyin", "work", GalleryDataAccess.ADMIN_ONLY);

        assertThat(adminOnly.dataAccess()).isEqualTo(GalleryDataAccess.ADMIN_ONLY);
        assertThatNullPointerException().isThrownBy(() -> new GalleryWorkDescriptor(
                        "douyin", "work", null))
                .withMessage("dataAccess");
    }
}
