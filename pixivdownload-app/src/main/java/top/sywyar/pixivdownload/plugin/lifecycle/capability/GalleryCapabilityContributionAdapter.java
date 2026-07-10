package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;

import java.util.ArrayList;
import java.util.List;

@Component
public class GalleryCapabilityContributionAdapter implements PluginContextCapabilityContributionAdapter {

    private final GalleryCapabilityRegistry registry;

    public GalleryCapabilityContributionAdapter(GalleryCapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String capabilityName() {
        return "gallery-projection-work-and-frontend";
    }

    @Override
    public void register(String pluginId, ConfigurableApplicationContext context) {
        List<GalleryProjectionProvider> projections = List.copyOf(
                context.getBeansOfType(GalleryProjectionProvider.class).values());
        List<GalleryWorkProvider> works = List.copyOf(
                context.getBeansOfType(GalleryWorkProvider.class).values());
        List<GalleryFrontendContribution> frontends = frontendContributions(pluginId, context);
        registry.register(pluginId, projections, works, frontends);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }

    private static List<GalleryFrontendContribution> frontendContributions(
            String pluginId, ConfigurableApplicationContext context) {
        List<GalleryFrontendContribution> contributions = new ArrayList<>();
        for (GalleryFrontendProvider provider
                : context.getBeansOfType(GalleryFrontendProvider.class).values()) {
            List<GalleryFrontendContribution> declared;
            try {
                declared = provider.frontendContributions();
            } catch (RuntimeException failure) {
                throw new IllegalStateException(
                        "failed to read gallery frontend contributions (owner: " + pluginId + ")", failure);
            }
            if (declared == null) {
                throw new IllegalStateException(
                        "gallery frontend provider returned null contributions (owner: " + pluginId + ")");
            }
            for (GalleryFrontendContribution contribution : declared) {
                if (contribution == null) {
                    throw new IllegalStateException(
                            "gallery frontend provider returned a null contribution (owner: " + pluginId + ")");
                }
                contributions.add(contribution);
            }
        }
        return List.copyOf(contributions);
    }
}
