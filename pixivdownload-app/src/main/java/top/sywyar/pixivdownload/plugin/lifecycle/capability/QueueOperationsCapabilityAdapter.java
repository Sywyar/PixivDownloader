package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;

import java.util.List;

/** Bridges owner-scoped queue operations from an external plugin child context into the host registry. */
@Component
public class QueueOperationsCapabilityAdapter implements PluginCapabilityContributionAdapter<QueueOperations> {

    private final QueueOperationRegistry registry;

    public QueueOperationsCapabilityAdapter(QueueOperationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<QueueOperations> beanType() {
        return QueueOperations.class;
    }

    @Override
    public void register(String pluginId, List<QueueOperations> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregisterOwner(pluginId);
    }
}
