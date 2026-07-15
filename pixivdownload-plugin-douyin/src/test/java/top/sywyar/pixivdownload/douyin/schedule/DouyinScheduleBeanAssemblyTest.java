package top.sywyar.pixivdownload.douyin.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.douyin.DouyinPluginConfiguration;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.download.DouyinMediaDownloader;
import top.sywyar.pixivdownload.douyin.download.work.DouyinWorkDownloadExecutor;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.schedule.credential.DouyinScheduledCredentialPolicy;
import top.sywyar.pixivdownload.douyin.schedule.guard.DouyinRiskExecutionGuard;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceDescriptors;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceExecutor;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceSupport;
import top.sywyar.pixivdownload.douyin.schedule.work.DouyinScheduledWorkExecutor;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("抖音计划 Spring Bean 装配")
class DouyinScheduleBeanAssemblyTest {

    private static final Set<String> CANONICAL_SOURCE_TYPES = Set.of(
            DouyinSourceTypes.USER,
            DouyinSourceTypes.SEARCH,
            DouyinSourceTypes.COLLECTION,
            DouyinSourceTypes.MUSIC,
            DouyinSourceTypes.ACCOUNT_OWN_WORKS,
            DouyinSourceTypes.ACCOUNT_LIKED_WORKS,
            DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
            DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION);

    @Test
    @DisplayName("描述符与来源执行器的规范集合精确相等且无重复")
    void descriptorAndExecutorCanonicalSetsAreExactlyEqual() {
        DouyinPluginConfiguration configuration = new DouyinPluginConfiguration();
        DouyinScheduleCodec codec = configuration.douyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceSupport support = configuration.douyinScheduledSourceSupport(
                mock(DouyinClient.class), codec,
                DouyinPluginSettingsService.fixed(
                        Path.of("target", "schedule-source-bean-test"),
                        DouyinProxyMode.INHERIT),
                new ProxyConfig());
        List<DouyinScheduledSourceExecutor> executors = List.of(
                configuration.douyinUserScheduledSourceExecutor(support),
                configuration.douyinSearchScheduledSourceExecutor(support),
                configuration.douyinCollectionScheduledSourceExecutor(support),
                configuration.douyinMusicScheduledSourceExecutor(support),
                configuration.douyinAccountOwnScheduledSourceExecutor(support),
                configuration.douyinAccountLikedScheduledSourceExecutor(support),
                configuration.douyinAccountFavoriteScheduledSourceExecutor(support),
                configuration.douyinAccountFavoriteCollectionScheduledSourceExecutor(support));

        Set<String> descriptorTypes = DouyinScheduledSourceDescriptors.createAll().stream()
                .map(descriptor -> descriptor.sourceType())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> executorTypes = executors.stream()
                .map(ScheduledSourceExecutor::sourceType)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertThat(DouyinScheduledSourceDescriptors.createAll()).hasSize(8);
        assertThat(executors).hasSize(8);
        assertThat(descriptorTypes).isEqualTo(CANONICAL_SOURCE_TYPES);
        assertThat(executorTypes).isEqualTo(CANONICAL_SOURCE_TYPES);
        assertThat(executorTypes).isEqualTo(descriptorTypes);
    }

    @Test
    @DisplayName("所有计划 Bean 工厂均受抖音开关控制并装配精确能力标识")
    void scheduleFactoriesAreConditionalAndExposeExactCapabilities() {
        Set<String> expectedFactoryNames = Set.of(
                "douyinScheduleCodec",
                "douyinScheduledSourceSupport",
                "douyinUserScheduledSourceExecutor",
                "douyinSearchScheduledSourceExecutor",
                "douyinCollectionScheduledSourceExecutor",
                "douyinMusicScheduledSourceExecutor",
                "douyinAccountOwnScheduledSourceExecutor",
                "douyinAccountLikedScheduledSourceExecutor",
                "douyinAccountFavoriteScheduledSourceExecutor",
                "douyinAccountFavoriteCollectionScheduledSourceExecutor",
                "douyinScheduledCredentialPolicy",
                "douyinRiskExecutionGuard",
                "douyinWorkDownloadExecutor",
                "douyinScheduledWorkExecutor");
        Set<String> actualFactoryNames = new LinkedHashSet<>();
        for (Method method : DouyinPluginConfiguration.class.getDeclaredMethods()) {
            if (!isScheduleFactory(method)) {
                continue;
            }
            actualFactoryNames.add(method.getName());
            assertThat(method.getAnnotation(Bean.class)).as(method.getName()).isNotNull();
            ConditionalOnPluginEnabled conditional =
                    method.getAnnotation(ConditionalOnPluginEnabled.class);
            assertThat(conditional).as(method.getName()).isNotNull();
            assertThat(conditional.value()).as(method.getName()).isEqualTo("douyin");
        }
        assertThat(actualFactoryNames).isEqualTo(expectedFactoryNames);

        DouyinPluginConfiguration configuration = new DouyinPluginConfiguration();
        DouyinClient client = mock(DouyinClient.class);
        DouyinScheduledCredentialPolicy credential =
                configuration.douyinScheduledCredentialPolicy(client);
        DouyinRiskExecutionGuard guard = configuration.douyinRiskExecutionGuard();
        DouyinHistoryService history = mock(DouyinHistoryService.class);
        DouyinWorkDownloadExecutor sharedWorkExecutor =
                configuration.douyinWorkDownloadExecutor(history);
        DownloadConfig downloadConfig = new DownloadConfig();
        downloadConfig.setMaxConcurrent(4);
        DouyinScheduledWorkExecutor work = configuration.douyinScheduledWorkExecutor(
                client,
                mock(DouyinMediaDownloader.class),
                sharedWorkExecutor,
                DouyinPluginSettingsService.fixed(Path.of("target", "schedule-bean-test"),
                        DouyinProxyMode.INHERIT),
                configuration.douyinScheduleCodec(new ObjectMapper()),
                downloadConfig);

        assertThat(credential.policyId()).isEqualTo("douyin.cookie");
        assertThat(guard.guardId()).isEqualTo("douyin.risk");
        assertThat(work.workType()).isEqualTo("douyin");
        assertThat(work.maxConcurrency()).isEqualTo(4);
    }

    private static boolean isScheduleFactory(Method method) {
        Class<?> type = method.getReturnType();
        return type == DouyinScheduleCodec.class
                || type == DouyinScheduledSourceSupport.class
                || ScheduledSourceExecutor.class.isAssignableFrom(type)
                || ScheduledCredentialPolicy.class.isAssignableFrom(type)
                || ScheduledExecutionGuard.class.isAssignableFrom(type)
                || ScheduledWorkExecutor.class.isAssignableFrom(type)
                || type == DouyinWorkDownloadExecutor.class;
    }
}
