package top.sywyar.pixivdownload.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ai.controller.AiStatusController;
import top.sywyar.pixivdownload.ai.controller.AiTestController;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

import java.util.function.Supplier;

@Configuration
public class AiPluginConfiguration {

    @Bean
    public AiPlugin aiPlugin() {
        return new AiPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public AiConfig aiConfig(Environment environment) {
        return bind(environment, "ai", AiConfig::new, AiConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public MessageResolver aiPluginMessages(MessageResolver messages) {
        return ResourceBundleMessageResolver.of(messages, AiPlugin.class.getClassLoader(),
                "i18n.ai.messages", "i18n.web.ai");
    }

    @Bean
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public AiPresetRegistry aiPresetRegistry() {
        return new AiPresetRegistry();
    }

    @Bean
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public OpenAiCompatibleAiClient openAiCompatibleAiClient(AiConfig aiConfig,
                                                             @Qualifier("aiPluginMessages") MessageResolver messages,
                                                             @Qualifier("aiRestTemplate") RestTemplate aiRestTemplate,
                                                             @Qualifier("aiProxyRestTemplate") RestTemplate aiProxyRestTemplate) {
        return new OpenAiCompatibleAiClient(aiConfig, messages, aiRestTemplate, aiProxyRestTemplate);
    }

    @Bean
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public AiTestController aiTestController(AiChatClient aiClient) {
        return new AiTestController(aiClient);
    }

    @Bean
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public AiStatusController aiStatusController(AiChatClient aiClient) {
        return new AiStatusController(aiClient);
    }

    private static <T> T bind(Environment environment, String prefix, Supplier<T> fallback, Class<T> type) {
        return Binder.get(environment).bind(prefix, Bindable.of(type)).orElseGet(fallback);
    }
}
