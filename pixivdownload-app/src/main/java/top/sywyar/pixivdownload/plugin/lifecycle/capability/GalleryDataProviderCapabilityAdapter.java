package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.GalleryDataProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryProviderRegistry;

import java.util.List;

@Component
public class GalleryDataProviderCapabilityAdapter implements PluginCapabilityContributionAdapter<GalleryDataProvider> {

    private final GalleryProviderRegistry registry;

    public GalleryDataProviderCapabilityAdapter(GalleryProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<GalleryDataProvider> beanType() {
        return GalleryDataProvider.class;
    }

    @Override
    public void register(String pluginId, List<GalleryDataProvider> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }
}
