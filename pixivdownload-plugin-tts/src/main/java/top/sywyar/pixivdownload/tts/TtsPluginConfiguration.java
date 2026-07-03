package top.sywyar.pixivdownload.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.config.DebugConfig;
import top.sywyar.pixivdownload.core.ai.AiService;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoiceService;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationScriptService;
import top.sywyar.pixivdownload.novel.narration.audio.NarrationAudioService;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.setup.guest.GuestInviteRateLimitSettings;
import top.sywyar.pixivdownload.tts.controller.TtsController;
import top.sywyar.pixivdownload.tts.narration.controller.NarrationController;
import top.sywyar.pixivdownload.tts.narration.controller.NarrationReferenceVoiceController;
import top.sywyar.pixivdownload.tts.narration.controller.NarrationTtsController;
import top.sywyar.pixivdownload.tts.narration.engine.CosyVoiceNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.DoubaoNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.ElevenLabsNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.FishNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.MiMoNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.MiniMaxNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.QwenNarrationEngine;
import top.sywyar.pixivdownload.tts.narration.engine.TtsPluginConfig;
import top.sywyar.pixivdownload.tts.narration.engine.VoxCpmNarrationEngine;

import java.util.function.Supplier;

@Configuration
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
    public MessageResolver ttsPluginMessages(MessageResolver messages) {
        return ResourceBundleMessageResolver.of(messages, TtsPlugin.class.getClassLoader(),
                "i18n.tts.messages", "i18n.web.tts");
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public EdgeTtsVersionService edgeTtsVersionService(@Qualifier("restTemplate") RestTemplate restTemplate,
                                                       ObjectMapper objectMapper,
                                                       @Qualifier("ttsPluginMessages") MessageResolver messages,
                                                       RuntimePathProvider runtimePathProvider) {
        return new EdgeTtsVersionService(restTemplate, objectMapper, messages, runtimePathProvider);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public EdgeTtsClient edgeTtsClient(OutboundProxySettings proxySettings,
                                       EdgeTtsVersionService versionService,
                                       @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new EdgeTtsClient(proxySettings, versionService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public EdgeTtsVoiceService edgeTtsVoiceService(@Qualifier("restTemplate") RestTemplate restTemplate,
                                                   ObjectMapper objectMapper,
                                                   EdgeTtsVersionService versionService,
                                                   @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new EdgeTtsVoiceService(restTemplate, objectMapper, versionService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public TtsRateLimitService ttsRateLimitService(GuestInviteRateLimitSettings settings) {
        return new TtsRateLimitService(settings);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public TtsController ttsController(EdgeTtsClient edgeTtsClient,
                                       EdgeTtsVoiceService voiceService,
                                       TtsRateLimitService rateLimitService,
                                       @Qualifier("ttsPluginMessages") MessageResolver messages) {
        return new TtsController(edgeTtsClient, voiceService, rateLimitService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public NarrationController narrationController(NovelNarrationScriptService scriptService,
                                                   NovelNarrationCastService castService,
                                                   NarrationReferenceVoiceService referenceVoiceService,
                                                   NarrationAudioService narrationAudioService,
                                                   NovelDatabase novelDatabase,
                                                   AppMessages messages,
                                                   DebugConfig debugConfig,
                                                   AiService aiService) {
        return new NarrationController(scriptService, castService, referenceVoiceService, narrationAudioService,
                novelDatabase, messages, debugConfig, aiService);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public NarrationTtsController narrationTtsController(NarrationAudioService narrationAudioService,
                                                         NovelNarrationScriptService narrationScriptService,
                                                         AppMessages messages) {
        return new NarrationTtsController(narrationAudioService, narrationScriptService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public NarrationReferenceVoiceController narrationReferenceVoiceController(
            NovelNarrationCastService castService,
            NarrationReferenceVoiceService referenceVoiceService,
            AppMessages messages) {
        return new NarrationReferenceVoiceController(castService, referenceVoiceService, messages);
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
