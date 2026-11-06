package top.sywyar.pixivdownload.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.api.download.queue.*;
import top.sywyar.pixivdownload.plugin.api.http.*;
import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.plugin.runtime.http.*;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled(TranslationPlugin.ID)
public class TranslationPluginConfiguration {
    @Bean public TranslationPlugin translationPlugin() { return new TranslationPlugin(); }
    @Bean public TranslationConfig translationConfig(Environment environment) {
        var config = Binder.get(environment).bind("translation", Bindable.of(TranslationConfig.class)).orElseGet(TranslationConfig::new);
        config.validate();
        return config;
    }
    @Bean public MessageResolver translationMessages(MessageResolver parent, LocaleBundlePolicy policy) {
        return ResourceBundleMessageResolver.of(parent, TranslationPlugin.class.getClassLoader(), policy,
                "i18n.translation.messages", "i18n.web.translate");
    }
    @Bean public TranslationDocuments translationDocuments(PluginServices services) { return new TranslationDocuments(services); }
    @Bean(name = "translationYoudaoHttp", destroyMethod = "close")
    public ManagedPluginRestTemplate youdaoHttp(OutboundHttpClientFactory factory, TranslationConfig config) { return http(factory, config.getYoudao()); }
    @Bean(name = "translationLibretranslateHttp", destroyMethod = "close")
    public ManagedPluginRestTemplate libretranslateHttp(OutboundHttpClientFactory factory, TranslationConfig config) { return http(factory, config.getLibretranslate()); }
    private ManagedPluginRestTemplate http(OutboundHttpClientFactory factory, TranslationConfig.MachineSettings settings) {
        var route = settings.isUseProxy() ? OutboundHttpRoute.configuredProxy() : OutboundHttpRoute.direct();
        int connections = settings.getRequestConcurrency() + 1;
        return PluginRestTemplateAdapter.open(factory, new OutboundHttpClientProfile(
                Duration.ofSeconds(30), Duration.ofSeconds(settings.getReadTimeoutSeconds()), route,
                OutboundHttpRedirectPolicy.NEVER, OutboundHttpCookiePolicy.DISABLED, connections, connections));
    }
    @Bean public MachineTranslationClient machineTranslationClient(TranslationConfig config,
            @Qualifier("translationYoudaoHttp") ManagedPluginRestTemplate youdao,
            @Qualifier("translationLibretranslateHttp") ManagedPluginRestTemplate libretranslate, ObjectMapper mapper) {
        return new MachineTranslationClient(config, youdao, libretranslate, mapper);
    }
    @Bean public NovelTranslationService novelTranslationService(TranslationConfig config, AiChatClient ai,
            TranslationDocuments documents, MachineTranslationClient machine,
            @Qualifier("translationMessages") MessageResolver messages, DatabaseAvailability database) {
        return new NovelTranslationService(config, ai, documents, machine, messages, database);
    }
    @Bean public QueueTaskTracker translationTaskTracker() { return new QueueTaskTracker("translation"); }
    @Bean("translationTaskExecutor") public ThreadPoolTaskExecutor translationTaskExecutor(TranslationConfig config) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, config.getMaxConcurrent()));
        executor.setMaxPoolSize(Math.max(1, config.getMaxConcurrent()));
        executor.setThreadNamePrefix("pixiv-translation-");
        executor.setQueueCapacity(TranslationJobStore.MAX_JOBS);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }
    @Bean public QueueOperations translationQueueOperations(QueueTaskTracker tracker) {
        return new TranslationQueueOperations(tracker);
    }
    @Bean public TranslationJobStore translationJobStore(top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase database, ObjectMapper mapper) {
        return new TranslationJobStore(database, mapper);
    }
    @Bean public TranslationJobs translationJobs(TranslationJobStore store, NovelTranslationService translations,
            TranslationDocuments documents, @Qualifier("translationTaskExecutor") ThreadPoolTaskExecutor executor, QueueTaskTracker tracker) {
        return new TranslationJobs(store, translations, documents, executor, tracker);
    }
    @Bean public TranslationOperations translationOperations(NovelTranslationService translations, TranslationDocuments documents,
            TranslationJobs jobs) {
        return new TranslationOperations(translations, documents, jobs);
    }
    @Bean public TranslationController translationController(NovelTranslationService translations,
            TranslationOperations operations, PluginServices services, @Qualifier("translationMessages") MessageResolver messages,
            TranslationJobs jobs) {
        return new TranslationController(translations, operations, services, messages, jobs);
    }
}
