package top.sywyar.pixivdownload.duplicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexQuery;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

/**
 * duplicate 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController} 与
 * {@code MaintenanceTask}）均经 {@code @PluginManagedBean} 排除出根包扫描，由这里以
 * {@code @Bean} 显式提供。疑似重复查询、扫描与回填只消费核心图片哈希索引的查询 / 重建语义端口，
 * 数据库、Mapper、作品记录与哈希写入实现均留在宿主应用层。
 * <p>
 * <b>禁用语义（{@code plugins.duplicate.enabled}）：本插件的全部业务 Bean 都随开关装配 / 缺席。</b>
 * 下面四个 {@code @Bean} 方法一律标 {@link ConditionalOnPluginEnabled}（descriptor {@link DuplicatePlugin}
 * 始终注册除外），禁用 duplicate 时它们全部缺席——页面 / API 因「未声明即 404」不可达、回填维护任务不在场
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
    public DuplicatePlugin duplicatePlugin() {
        return new DuplicatePlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public MessageResolver duplicatePluginMessages(MessageResolver messages) {
        return ResourceBundleMessageResolver.of(
                messages, DuplicatePlugin.class.getClassLoader(), "i18n.web.duplicates");
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateService duplicateService(ArtworkHashIndexQuery hashIndexQuery,
                                             @Qualifier("duplicatePluginMessages") MessageResolver messages) {
        return new DuplicateService(hashIndexQuery, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateScanService duplicateScanService(ArtworkHashIndexMaintenance hashIndexMaintenance,
                                                     DuplicateService duplicateService,
                                                     @Qualifier("duplicatePluginMessages") MessageResolver messages,
                                                     @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        return new DuplicateScanService(hashIndexMaintenance, duplicateService, messages, taskExecutor);
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateController duplicateController(DuplicateService duplicateService,
                                                   DuplicateScanService duplicateScanService) {
        return new DuplicateController(duplicateService, duplicateScanService);
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateHashBackfillTask duplicateHashBackfillTask(
            ArtworkHashIndexMaintenance hashIndexMaintenance,
            DuplicateService duplicateService,
            @Qualifier("duplicatePluginMessages") MessageResolver messages) {
        return new DuplicateHashBackfillTask(hashIndexMaintenance, duplicateService, messages);
    }
}
