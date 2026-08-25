package top.sywyar.pixivdownload.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClient;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.tts.controller.TtsController;
import top.sywyar.pixivdownload.tts.http.TtsHttpClientConfiguration;
import top.sywyar.pixivdownload.tts.narration.engine.TtsPluginConfig;
import top.sywyar.pixivdownload.tts.narration.engine.cosyvoice.CosyVoiceNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.doubao.DoubaoNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.elevenlabs.ElevenLabsNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.fish.FishNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.mimo.MiMoNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.minimax.MiniMaxNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.qwen.QwenNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.voxcpm.VoxCpmNarrationEngine;

import java.util.function.Supplier;

@Configuration
@Import(TtsHttpClientConfiguration.class)
public class TtsPluginConfiguration {

    @Bean
    public TtsPlugin ttsPlugin() {
        return new TtsPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public TtsPluginConfig ttsPluginConfig(Environment environment) {
        return bind(environment, "narration-tts", TtsPluginConfig::new, TtsPluginConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public TtsGuestRateLimitConfig ttsGuestRateLimitConfig(Environment environment) {
        return bind(environment, "guest-invite", TtsGuestRateLimitConfig::new,
                TtsGuestRateLimitConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public MessageResolver ttsPluginMessages(MessageResolver messages, LocaleBundlePolicy localeBundlePolicy) {
        return ResourceBundleMessageResolver.of(messages, TtsPlugin.class.getClassLoader(), localeBundlePolicy,
                "i18n.tts.messages", "i18n.web.tts");
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public TtsRuntimeFiles ttsRuntimeFiles(RuntimePathProvider runtimePathProvider) {
        return new TtsRuntimeFiles(runtimePathProvider);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public EdgeTtsVersionService edgeTtsVersionService(
            @Qualifier("ttsMetadataRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Qualifier("ttsPluginMessages") MessageResolver messages,
            TtsRuntimeFiles runtimeFiles) {
        return new EdgeTtsVersionService(restTemplate, objectMapper, messages, runtimeFiles);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public EdgeTtsWebSocketConnector edgeTtsWebSocketConnector(
            @Qualifier("edgeTtsWebSocketClient") OutboundWebSocketClient client,
            EdgeTtsVersionService versionService
    ) {
        return new DefaultEdgeTtsWebSocketConnector(client, versionService);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public EdgeTtsClient edgeTtsClient(EdgeTtsVersionService versionService,
                                       EdgeTtsWebSocketConnector connector,
                                       @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new EdgeTtsClient(versionService, connector, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public EdgeTtsVoiceService edgeTtsVoiceService(
            @Qualifier("ttsMetadataRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            EdgeTtsVersionService versionService,
            @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new EdgeTtsVoiceService(restTemplate, objectMapper, versionService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public TtsRateLimitService ttsRateLimitService(TtsGuestRateLimitConfig config) {
        return new TtsRateLimitService(config);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public TtsController ttsController(EdgeTtsClient edgeTtsClient,
                                       EdgeTtsVoiceService voiceService,
                                       TtsRateLimitService rateLimitService,
                                       @Qualifier("ttsPluginMessages") MessageResolver messages,
                                       RequestOwnerIdentityResolver requestOwnerIdentityResolver) {
        return new TtsController(edgeTtsClient, voiceService, rateLimitService, messages,
                requestOwnerIdentityResolver);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public VoxCpmNarrationEngine voxCpmNarrationEngine(TtsPluginConfig config,
                                                       @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                       @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                       @Qualifier("narrationTtsProbeRestTemplate") RestTemplate directProbeRestTemplate,
                                                       @Qualifier("narrationTtsProbeProxyRestTemplate") RestTemplate proxyProbeRestTemplate,
                                                       @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new VoxCpmNarrationEngine(config, directRestTemplate, proxyRestTemplate,
                directProbeRestTemplate, proxyProbeRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public MiMoNarrationEngine miMoNarrationEngine(TtsPluginConfig config,
                                                   @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                   @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                   @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new MiMoNarrationEngine(config, directRestTemplate, proxyRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public CosyVoiceNarrationEngine cosyVoiceNarrationEngine(TtsPluginConfig config,
                                                             @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                             @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                             @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new CosyVoiceNarrationEngine(config, directRestTemplate, proxyRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public FishNarrationEngine fishNarrationEngine(TtsPluginConfig config,
                                                   @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                   @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                   @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new FishNarrationEngine(config, directRestTemplate, proxyRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public MiniMaxNarrationEngine miniMaxNarrationEngine(TtsPluginConfig config,
                                                         @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                         @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                         @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new MiniMaxNarrationEngine(config, directRestTemplate, proxyRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public ElevenLabsNarrationEngine elevenLabsNarrationEngine(TtsPluginConfig config,
                                                               @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                               @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                               @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new ElevenLabsNarrationEngine(config, directRestTemplate, proxyRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public QwenNarrationEngine qwenNarrationEngine(TtsPluginConfig config,
                                                   @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                   @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                   @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new QwenNarrationEngine(config, directRestTemplate, proxyRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public DoubaoNarrationEngine doubaoNarrationEngine(TtsPluginConfig config,
                                                       @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                                       @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                                       @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new DoubaoNarrationEngine(config, directRestTemplate, proxyRestTemplate, messages);
    }

    private static <T> T bind(Environment environment, String prefix, Supplier<T> fallback, Class<T> type) {
        return Binder.get(environment).bind(prefix, Bindable.of(type)).orElseGet(fallback);
    }
}
