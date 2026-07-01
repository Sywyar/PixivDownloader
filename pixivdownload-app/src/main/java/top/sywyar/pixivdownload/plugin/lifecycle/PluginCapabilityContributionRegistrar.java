package top.sywyar.pixivdownload.plugin.lifecycle;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.AiChatClientRegistry;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.notification.NotificationSinkRegistry;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelRegistry;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationEngineRegistry;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;

import java.util.List;

/**
 * Registers runtime capability beans contributed by an external plugin child context.
 */
@Component
public class PluginCapabilityContributionRegistrar {

    private final NotificationSinkRegistry notificationSinkRegistry;
    private final PushChannelRegistry pushChannelRegistry;
    private final AiChatClientRegistry aiChatClientRegistry;
    private final NarrationEngineRegistry narrationEngineRegistry;

    public PluginCapabilityContributionRegistrar(NotificationSinkRegistry notificationSinkRegistry,
                                                 PushChannelRegistry pushChannelRegistry,
                                                 AiChatClientRegistry aiChatClientRegistry,
                                                 NarrationEngineRegistry narrationEngineRegistry) {
        this.notificationSinkRegistry = notificationSinkRegistry;
        this.pushChannelRegistry = pushChannelRegistry;
        this.aiChatClientRegistry = aiChatClientRegistry;
        this.narrationEngineRegistry = narrationEngineRegistry;
    }

    public void register(String pluginId, ConfigurableApplicationContext context) {
        if (context == null) {
            unregister(pluginId);
            return;
        }
        notificationSinkRegistry.register(pluginId, beans(context, NotificationSink.class));
        pushChannelRegistry.register(pluginId, beans(context, PushChannel.class));
        aiChatClientRegistry.register(pluginId, beans(context, AiChatClient.class));
        narrationEngineRegistry.register(pluginId, beans(context, NarrationVoiceEngine.class));
    }

    public void unregister(String pluginId) {
        notificationSinkRegistry.unregister(pluginId);
        pushChannelRegistry.unregister(pluginId);
        aiChatClientRegistry.unregister(pluginId);
        narrationEngineRegistry.unregister(pluginId);
    }

    private static <T> List<T> beans(ConfigurableApplicationContext context, Class<T> type) {
        return List.copyOf(context.getBeansOfType(type).values());
    }
}
