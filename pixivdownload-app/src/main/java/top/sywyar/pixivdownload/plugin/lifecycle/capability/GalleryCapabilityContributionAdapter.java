package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;

import java.util.List;

@Component
public class GalleryCapabilityContributionAdapter implements PluginContextCapabilityContributionAdapter {

    private final GalleryCapabilityRegistry registry;

    public GalleryCapabilityContributionAdapter(GalleryCapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String capabilityName() {
        return "gallery-projection-and-work";
    }

    @Override
    public void register(String pluginId, ConfigurableApplicationContext context) {
        List<GalleryProjectionProvider> projections = List.copyOf(
                context.getBeansOfType(GalleryProjectionProvider.class).values());
        List<GalleryWorkProvider> works = List.copyOf(
                context.getBeansOfType(GalleryWorkProvider.class).values());
        registry.register(pluginId, projections, works);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }
}
