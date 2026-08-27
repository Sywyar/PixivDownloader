package top.sywyar.pixivdownload.duplicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexQuery;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

/**
 * gallery-tools 中疑似重复能力的 Bean 装配收敛点：业务 Bean（含 {@code @RestController} 与
 * {@code MaintenanceTask}）均经 {@code @PluginManagedBean} 排除出根包扫描，由这里以
 * {@code @Bean} 显式提供。疑似重复查询、扫描与回填只消费核心图片哈希索引的查询 / 重建语义端口，
 * 数据库、Mapper、作品记录与哈希写入实现均留在宿主应用层。
 * <p>
 * <b>禁用语义（{@code plugins.gallery-tools.enabled}）：本能力的全部业务 Bean 都随插件开关装配 / 缺席。</b>
 * 禁用 gallery-tools 时它们全部缺席——页面 / API 因「未声明即 404」不可达、回填维护任务不在场
 * （核心维护任务仍执行）：
 * <ul>
 *   <li>{@link DuplicateService}：疑似重复分组查询 + 缓存（缓存按数据库 fingerprint 自失效）。</li>
 *   <li>{@link DuplicateScanService}：手动重扫。</li>
 *   <li>{@link DuplicateController}：页面 API。</li>
 *   <li>{@link DuplicateHashBackfillTask}：缺失 Hash 批量回填维护任务。</li>
 * </ul>
 * <b>核心 Hash 写入接缝不在本插件托管范围。</b>「下载后即时算 Hash」仍是宿主核心资产索引链路，
 * 不属任何功能插件、不随 duplicate 禁用；重扫 / 回填只经中性重建端口复用该能力，自身仍随插件开关缺席。
 */
@Configuration
public class DuplicatePluginConfiguration {

    @Bean
    @ConditionalOnPluginEnabled("gallery-tools")
    public MessageResolver duplicatePluginMessages(MessageResolver messages, LocaleBundlePolicy localeBundlePolicy) {
        return ResourceBundleMessageResolver.of(
                messages, DuplicatePluginConfiguration.class.getClassLoader(), localeBundlePolicy,
                "i18n.web.duplicates");
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery-tools")
    public DuplicateService duplicateService(ArtworkHashIndexQuery hashIndexQuery,
                                              @Qualifier("duplicatePluginMessages") MessageResolver messages) {
        return new DuplicateService(hashIndexQuery, messages);
    }

    @Bean(name = "duplicateScanTaskExecutor", destroyMethod = "shutdown")
    @ConditionalOnPluginEnabled("gallery-tools")
    public ThreadPoolTaskExecutor duplicateScanTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("duplicate-scan-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery-tools")
    public DuplicateScanService duplicateScanService(ArtworkHashIndexMaintenance hashIndexMaintenance,
                                                      DuplicateService duplicateService,
                                                      @Qualifier("duplicatePluginMessages") MessageResolver messages,
                                                      @Qualifier("duplicateScanTaskExecutor") TaskExecutor taskExecutor) {
        return new DuplicateScanService(hashIndexMaintenance, duplicateService, messages, taskExecutor);
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery-tools")
    public DuplicateController duplicateController(DuplicateService duplicateService,
                                                   DuplicateScanService duplicateScanService) {
        return new DuplicateController(duplicateService, duplicateScanService);
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery-tools")
    public DuplicateHashBackfillTask duplicateHashBackfillTask(
            ArtworkHashIndexMaintenance hashIndexMaintenance,
            DuplicateService duplicateService,
            @Qualifier("duplicatePluginMessages") MessageResolver messages) {
        return new DuplicateHashBackfillTask(hashIndexMaintenance, duplicateService, messages);
    }
}
