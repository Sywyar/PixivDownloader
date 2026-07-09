package top.sywyar.pixivdownload.core.gallery.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("统一画廊作品详情访问隔离")
class GalleryWorkBrokerTest {

    @Test
    @DisplayName("默认详情查询排除管理数据且显式管理查询可以访问")
    void filtersAdministrativeWorkWithoutSourceSpecificKnowledge() {
        GalleryWorkKey key = new GalleryWorkKey("source-a", "work", "1");
        GalleryWorkProvider provider = new GalleryWorkProvider() {
            @Override public String providerId() { return "provider-a"; }
            @Override public List<GalleryWorkDescriptor> works() {
                return List.of(new GalleryWorkDescriptor("source-a", "work", GalleryDataAccess.ADMIN_ONLY));
            }
            @Override public Optional<GalleryWork> find(GalleryWorkKey requested) {
                return Optional.of(new GalleryWork(requested, "title", null, null, List.of(),
                        null, null, null, null, null, List.of(), Map.of()));
            }
        };
        GalleryWorkBroker broker = new GalleryWorkBroker(
                new GalleryCapabilityRegistry(List.of(), List.of(provider)));

        assertThat(broker.find(key).work()).isEmpty();
        assertThat(broker.find(key, Set.of(GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY)).work())
                .isPresent();
    }
}
