package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryProjectionBroker;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryWorkBroker;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("统一画廊只读 API")
class UnifiedGalleryControllerTest {

    @Test
    @DisplayName("非管理员 descriptors 不暴露管理员专属投影和作品命名空间")
    void sharedDescriptorsExcludeAdministrativeData() {
        GalleryCapabilityRegistry registry = mock(GalleryCapabilityRegistry.class);
        when(registry.snapshot()).thenReturn(new GalleryCapabilityRegistry.Snapshot(
                List.of(), List.of(), List.of(
                descriptor("shared", GalleryDataAccess.SHARED),
                descriptor("admin", GalleryDataAccess.ADMIN_ONLY)),
                List.of(
                        new GalleryWorkDescriptor("shared", "work", GalleryDataAccess.SHARED),
                        new GalleryWorkDescriptor("admin", "work", GalleryDataAccess.ADMIN_ONLY)),
                List.of()));
        SetupService setup = mock(SetupService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(setup.hasAdminScope(request)).thenReturn(false);
        UnifiedGalleryController controller = new UnifiedGalleryController(registry,
                mock(GalleryProjectionBroker.class), mock(GalleryWorkBroker.class), setup);

        var response = controller.descriptors(request);

        assertThat(response.projections()).extracting(GalleryProjectionDescriptor::sourceId)
                .containsExactly("shared");
        assertThat(response.works()).extracting(GalleryWorkDescriptor::sourceId)
                .containsExactly("shared");
    }

    private static GalleryProjectionDescriptor descriptor(String source, GalleryDataAccess access) {
        return new GalleryProjectionDescriptor(source, GalleryKind.IMAGE,
                "gallery", "source.test", 1, access, Map.of());
    }
}
