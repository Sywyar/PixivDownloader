package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.notification.NotificationTemplateRegistry;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 把插件 child context 的模板贡献一次性物化为宿主纯值快照。 */
@Component
public class NotificationTemplateCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {

    static final int MAX_TEMPLATES_PER_PLUGIN = 256;
    static final int MAX_TOTAL_TEMPLATE_BYTES = 8 * 1_024 * 1_024;

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<NotificationTemplateContribution> templates
    ) implements PreparedContribution {
        private Prepared {
            templates = List.copyOf(templates);
        }
    }

    private final NotificationTemplateRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    public NotificationTemplateCapabilityAdapter(NotificationTemplateRegistry registry) {
        this(registry, new ExternalCapabilityInvocationRegistry());
    }

    @Autowired
    public NotificationTemplateCapabilityAdapter(
            NotificationTemplateRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public String capabilityName() {
        return NotificationTemplateContributor.class.getName();
    }

    @Override
    public PreparedContribution prepare(
            ExternalCapabilityPreparation preparation,
            ConfigurableApplicationContext context) {
        List<NotificationTemplateContribution> templates = new ArrayList<>();
        for (NotificationTemplateContributor contributor :
                context.getBeansOfType(NotificationTemplateContributor.class).values()) {
            List<NotificationTemplateContribution> contributed = invocationRegistry.captureMetadata(
                    preparation,
                    NotificationTemplateContributor.class,
                    "notification templates",
                    contributor::notificationTemplates);
            if (contributed == null) {
                throw new IllegalStateException("notification template contributor returned null");
            }
            templates.addAll(contributed);
        }
        validateSize(templates);
        return new Prepared(preparation.owner(), templates);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(
                prepared.owner().pluginId(), prepared.owner().publicationId(), prepared.templates());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner.pluginId(), owner.publicationId());
    }

    private static void validateSize(List<NotificationTemplateContribution> templates) {
        if (templates.size() > MAX_TEMPLATES_PER_PLUGIN) {
            throw new IllegalArgumentException("notification template count exceeds limit");
        }
        long bytes = 0L;
        for (NotificationTemplateContribution template : templates) {
            if (template == null) {
                throw new IllegalArgumentException("notification template must not be null");
            }
            bytes += utf8Bytes(template.scenarioId());
            bytes += utf8Bytes(template.medium());
            bytes += utf8Bytes(template.locale().toLanguageTag());
            bytes += utf8Bytes(template.titleTemplate());
            bytes += utf8Bytes(template.bodyTemplate());
            if (bytes > MAX_TOTAL_TEMPLATE_BYTES) {
                throw new IllegalArgumentException("notification template bytes exceed limit");
            }
        }
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared notification template contribution");
    }
}
