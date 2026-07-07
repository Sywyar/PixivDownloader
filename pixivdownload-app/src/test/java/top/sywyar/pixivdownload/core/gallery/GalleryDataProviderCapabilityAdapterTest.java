package top.sywyar.pixivdownload.core.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryQuery;
import top.sywyar.pixivdownload.core.gallery.model.GallerySourceDescriptor;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.GalleryDataProviderCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("画廊数据提供方运行期能力适配器")
class GalleryDataProviderCapabilityAdapterTest {

    @Test
    @DisplayName("注册器从子上下文发现画廊数据提供方并在注销后清除")
    void registrarDiscoversGalleryProviderFromChildContextAndUnregistersIt() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of());
        GalleryDataProviderCapabilityAdapter adapter = new GalleryDataProviderCapabilityAdapter(registry);
        PluginCapabilityContributionRegistrar registrar =
                new PluginCapabilityContributionRegistrar(List.<PluginCapabilityContributionAdapter<?>>of(adapter));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("galleryProvider", GalleryDataProvider.class,
                    () -> provider("external-provider", "external-source", GalleryKind.IMAGE));
            child.refresh();

            registrar.register("external-gallery", child);
        }

        assertThat(adapter.beanType()).isEqualTo(GalleryDataProvider.class);
        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("external-source");

        registrar.unregister("external-gallery");

        assertThat(registry.snapshot().sources()).isEmpty();
    }

    private static GalleryDataProvider provider(String providerId, String sourceId, GalleryKind kind) {
        return new GalleryDataProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public List<GallerySourceDescriptor> sources() {
                return List.of(new GallerySourceDescriptor(
                        providerId, sourceId, Set.of(kind), "gallery", "source." + sourceId, 10, List.of()));
            }

            @Override
            public GalleryPage query(GalleryQuery query) {
                return GalleryPage.empty(query.offset(), query.limit(), List.of());
            }

            @Override
            public GalleryFacetPage facets(GalleryQuery query) {
                return GalleryFacetPage.empty();
            }
        };
    }
}
