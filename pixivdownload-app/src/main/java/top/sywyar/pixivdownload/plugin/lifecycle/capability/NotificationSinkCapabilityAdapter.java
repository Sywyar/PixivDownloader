package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.notification.NotificationSinkRegistry;

import java.util.List;

@Component
public class NotificationSinkCapabilityAdapter implements PluginCapabilityContributionAdapter<NotificationSink> {

    private final NotificationSinkRegistry registry;

    public NotificationSinkCapabilityAdapter(NotificationSinkRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<NotificationSink> beanType() {
        return NotificationSink.class;
    }

    @Override
    public void register(String pluginId, List<NotificationSink> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }
}
