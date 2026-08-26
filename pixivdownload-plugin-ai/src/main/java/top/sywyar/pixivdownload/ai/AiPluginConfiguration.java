package top.sywyar.pixivdownload.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ai.controller.AiModelsController;
import top.sywyar.pixivdownload.ai.controller.AiStatusController;
import top.sywyar.pixivdownload.ai.controller.AiTestController;
import top.sywyar.pixivdownload.ai.http.AiHttpClientConfiguration;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

import java.util.function.Supplier;

@Configuration
@Import(AiHttpClientConfiguration.class)
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
    public MessageResolver aiPluginMessages(MessageResolver messages, LocaleBundlePolicy localeBundlePolicy) {
        return ResourceBundleMessageResolver.of(messages, AiPlugin.class.getClassLoader(), localeBundlePolicy,
                "i18n.ai.messages", "i18n.web.ai");
    }

    @Bean
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public AiPresetRegistry aiPresetRegistry() {
        return new AiPresetRegistry();
    }

    /**
     * 本插件的 OpenAI 兼容 client，由宿主经 {@link AiChatClient} 能力适配发布为活动能力，插件子上下文内的
     * AI 控制器也按同一契约注入它。
     * <p>
     * 父上下文另暴露宿主门面 {@code AiService}（同样实现 {@link AiChatClient}，供其它插件经 registry 消费），
     * 因此本 Bean 必须标记 {@link Primary}：否则插件子上下文中 {@code AiChatClient} 类型的注入会同时命中
     * {@code aiService} 与本 Bean，导致 {@code NoUniqueBeanDefinitionException} 使插件无法启动。本插件的自有
     * client 是本上下文内该契约的默认目标；宿主门面语义不受影响。
     */
    @Bean
    @Primary
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
    public AiModelsController aiModelsController(OpenAiCompatibleAiClient aiClient) {
        return new AiModelsController(aiClient);
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
