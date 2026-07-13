package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.util.ArrayList;
import java.util.List;

@Component
public class GalleryCapabilityContributionAdapter implements PluginContextCapabilityContributionAdapter,
        ExternalRuntimeCapabilityAdapter {

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<GalleryCapabilityRegistry.PreparedProjectionProvider> projections,
            List<GalleryCapabilityRegistry.PreparedWorkProvider> works,
            List<GalleryFrontendContribution> frontends
    ) implements PreparedContribution {
        private Prepared {
            projections = List.copyOf(projections);
            works = List.copyOf(works);
            frontends = List.copyOf(frontends);
        }
    }

    private final GalleryCapabilityRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    public GalleryCapabilityContributionAdapter(GalleryCapabilityRegistry registry) {
        this(registry, new ExternalCapabilityInvocationRegistry());
    }

    @Autowired
    public GalleryCapabilityContributionAdapter(
            GalleryCapabilityRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
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

    @Override
    public PreparedContribution prepare(
            ExternalCapabilityPreparation preparation,
            ConfigurableApplicationContext context) {
        List<GalleryCapabilityRegistry.PreparedProjectionProvider> projections = context
                .getBeansOfType(GalleryProjectionProvider.class).values().stream()
                .map(target -> {
                    String providerId = invocationRegistry.captureMetadata(
                            preparation, GalleryProjectionProvider.class,
                            "projection provider id", target::providerId);
                    List<top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor>
                            descriptors = invocationRegistry.captureMetadata(
                            preparation, GalleryProjectionProvider.class,
                            "projection descriptors", target::projections);
                    GalleryProjectionProvider proxy = invocationRegistry.prepareProxy(
                            preparation, GalleryProjectionProvider.class, target);
                    return new GalleryCapabilityRegistry.PreparedProjectionProvider(
                            providerId, proxy, descriptors, target.getClass().getName());
                })
                .toList();
        List<GalleryCapabilityRegistry.PreparedWorkProvider> works = context
                .getBeansOfType(GalleryWorkProvider.class).values().stream()
                .map(target -> {
                    String providerId = invocationRegistry.captureMetadata(
                            preparation, GalleryWorkProvider.class,
                            "work provider id", target::providerId);
                    List<top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor>
                            descriptors = invocationRegistry.captureMetadata(
                            preparation, GalleryWorkProvider.class,
                            "work descriptors", target::works);
                    GalleryWorkProvider proxy = invocationRegistry.prepareProxy(
                            preparation, GalleryWorkProvider.class, target);
                    return new GalleryCapabilityRegistry.PreparedWorkProvider(
                            providerId, proxy, descriptors, target.getClass().getName());
                })
                .toList();
        List<GalleryFrontendContribution> frontends = new ArrayList<>();
        for (GalleryFrontendProvider target
                : context.getBeansOfType(GalleryFrontendProvider.class).values()) {
            List<GalleryFrontendContribution> declared = invocationRegistry.captureMetadata(
                    preparation, GalleryFrontendProvider.class,
                    "frontend contributions", target::frontendContributions);
            if (declared == null) {
                throw new IllegalStateException("gallery frontend provider returned null contributions (owner: "
                        + preparation.owner().pluginId() + ")");
            }
            if (declared.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalStateException("gallery frontend provider returned a null contribution (owner: "
                        + preparation.owner().pluginId() + ")");
            }
            frontends.addAll(declared);
        }
        return new Prepared(preparation.owner(), projections, works, frontends);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(
                prepared.owner().pluginId(),
                prepared.owner().publicationId(),
                prepared.projections(),
                prepared.works(),
                prepared.frontends());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner.pluginId(), owner.publicationId());
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared gallery capability contribution");
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
