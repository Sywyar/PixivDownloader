package top.sywyar.pixivdownload.core.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryMediaKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryProjectionKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaAsset;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryAiStatus;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryContentRating;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterCapability;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("统一画廊纯 Java 契约")
class GalleryContractTest {

    @Test
    @DisplayName("同一作品可拥有图片与视频两个列表投影且共享详情身份")
    void oneWorkSupportsMultipleProjectionKinds() {
        GalleryWorkKey workKey = new GalleryWorkKey("douyin", "aweme", "7301");

        assertThat(new GalleryProjectionKey(workKey, GalleryKind.IMAGE).workKey()).isEqualTo(workKey);
        assertThat(new GalleryProjectionKey(workKey, GalleryKind.VIDEO).workKey()).isEqualTo(workKey);
    }

    @Test
    @DisplayName("作品详情接受完整混合媒体集合并拒绝其它作品的媒体身份")
    void workOwnsCompleteMediaSet() {
        GalleryWorkKey workKey = new GalleryWorkKey("douyin", "aweme", "7301");
        GalleryMediaAsset image = asset(workKey, "file-0", GalleryMediaKind.IMAGE);
        GalleryMediaAsset video = asset(workKey, "file-1", GalleryMediaKind.LIVE_PHOTO_VIDEO);

        GalleryWork work = new GalleryWork(workKey, "title", null, null, List.of(), null, null, null,
                GalleryContentRating.SFW, GalleryAiStatus.UNKNOWN, List.of(image, video), Map.of());

        assertThat(work.media()).extracting(GalleryMediaAsset::kind)
                .containsExactly(GalleryMediaKind.IMAGE, GalleryMediaKind.LIVE_PHOTO_VIDEO);
        GalleryMediaAsset foreign = asset(new GalleryWorkKey("douyin", "aweme", "other"),
                "file-0", GalleryMediaKind.IMAGE);
        assertThatThrownBy(() -> new GalleryWork(workKey, "title", null, null, List.of(), null, null, null,
                GalleryContentRating.SFW, GalleryAiStatus.UNKNOWN, List.of(foreign), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("media work key");
    }

    @Test
    @DisplayName("UNKNOWN 能力不携带常量值且不能伪装成否定常量")
    void unknownCapabilityHasNoFalseFallback() {
        assertThat(GalleryFilterCapability.unknown().constantValues()).isEmpty();
        assertThatThrownBy(() -> new GalleryFilterCapability(
                top.sywyar.pixivdownload.core.gallery.model.GalleryFieldCapability.UNKNOWN,
                java.util.Set.of("false")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GalleryMediaAsset asset(GalleryWorkKey workKey, String mediaId, GalleryMediaKind kind) {
        return new GalleryMediaAsset(new GalleryMediaKey(workKey, mediaId), kind, null, null, null, null, Map.of());
    }
}
