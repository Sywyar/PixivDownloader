package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationCommands;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationOwner;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry.PreparedQueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.util.List;

/** Bridges owner-scoped queue operations from an external plugin child context into the host registry. */
@Component
public class QueueOperationsCapabilityAdapter implements PluginCapabilityContributionAdapter<QueueOperations>,
        ExternalRuntimeCapabilityAdapter {

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<PreparedQueueOperations> operations
    ) implements PreparedContribution {
        private Prepared {
            operations = List.copyOf(operations);
        }
    }

    private static final class RawQueueOperationCommands implements QueueOperationCommands {
        private final QueueOperations target;

        private RawQueueOperationCommands(QueueOperations target) {
            this.target = target;
        }

        @Override
        public void cancel(String workKey, String ownerUuid, boolean admin) {
            target.cancel(workKey, ownerUuid, admin);
        }

        @Override
        public int clearAll() {
            return target.clearAll();
        }

        @Override
        public int clearForOwner(String ownerUuid) {
            return target.clearForOwner(ownerUuid);
        }
    }

    private final QueueOperationRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    public QueueOperationsCapabilityAdapter(QueueOperationRegistry registry) {
        this(registry, new ExternalCapabilityInvocationRegistry());
    }

    @Autowired
    public QueueOperationsCapabilityAdapter(
            QueueOperationRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public Class<QueueOperations> beanType() {
        return QueueOperations.class;
    }

    @Override
    public String capabilityName() {
        return QueueOperations.class.getName();
    }

    @Override
    public void register(String pluginId, List<QueueOperations> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregisterOwner(pluginId);
    }

    @Override
    public PreparedContribution prepare(
            ExternalCapabilityPreparation preparation,
            ConfigurableApplicationContext context) {
        List<PreparedQueueOperations> operations = context
                .getBeansOfType(QueueOperations.class).values().stream()
                .map(target -> {
                    String queueType = invocationRegistry.captureMetadata(
                            preparation, QueueOperations.class, "queue type", target::queueType);
                    QueueOperationCommands proxy = invocationRegistry.prepareProxy(
                            preparation,
                            QueueOperationCommands.class,
                            new RawQueueOperationCommands(target));
                    return new PreparedQueueOperations(
                            queueType, target, proxy, target.getClass().getName());
                })
                .toList();
        return new Prepared(preparation.owner(), operations);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        ExternalCapabilityOwner owner = prepared.owner();
        registry.registerPrepared(new QueueOperationOwner(
                owner.pluginId(),
                owner.packageId(),
                owner.pluginGeneration(),
                owner.publicationId()), prepared.operations());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(new QueueOperationOwner(
                owner.pluginId(),
                owner.packageId(),
                owner.pluginGeneration(),
                owner.publicationId()));
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared queue operation capability contribution");
    }
}
