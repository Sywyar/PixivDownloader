package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelRegistry;

import java.util.List;

@Component
public class PushChannelCapabilityAdapter implements PluginCapabilityContributionAdapter<PushChannel> {

    private final PushChannelRegistry registry;

    public PushChannelCapabilityAdapter(PushChannelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<PushChannel> beanType() {
        return PushChannel.class;
    }

    @Override
    public void register(String pluginId, List<PushChannel> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }
}
