package top.sywyar.pixivdownload.duplicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.core.hash.ImageHashMapper;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

/**
 * duplicate 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController} 与
 * {@code MaintenanceTask}）均经 {@code @PluginManagedBean} 排除出根包扫描，由这里以
 * {@code @Bean} 显式提供。{@link ImageHashMapper} 是核心图片哈希数据域 mapper（住 {@code core.hash}、
 * MyBatis 自动扫描），疑似重复查询 / 扫描 / 回填经正向 {@code plugin→core} 依赖读取它。
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
 * <b>核心 Hash 写入接缝不在本插件托管范围。</b>「下载后即时算 Hash」是核心资产索引链路，已抽到核心服务
 * {@link ArtworkHashService}（{@code core.hash}，根包扫描 {@code @Service}，{@code ArtworkDownloadExecutor} 直接注入）。
 * 它不属任何功能插件、不随 duplicate 禁用——禁用 duplicate 后新下载作品仍照常写 Hash。重复检测重启后只补齐历史
 * 缺口与失败哨兵。重扫 / 回填沿用 {@link ArtworkHashService} 做计算（正向 {@code plugin→core} 依赖），但它们自身随开关缺席。
 */
@Configuration
public class DuplicatePluginConfiguration {

    @Bean
    public DuplicatePlugin duplicatePlugin() {
        return new DuplicatePlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateService duplicateService(ImageHashMapper imageHashMapper, AppMessages messages) {
        return new DuplicateService(imageHashMapper, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateScanService duplicateScanService(ImageHashMapper imageHashMapper,
                                                     ArtworkHashService artworkHashService,
                                                     DuplicateService duplicateService,
                                                     PixivDatabase pixivDatabase,
                                                     AppMessages messages,
                                                     @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        return new DuplicateScanService(
                imageHashMapper, artworkHashService, duplicateService, pixivDatabase, messages, taskExecutor);
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateController duplicateController(DuplicateService duplicateService,
                                                   DuplicateScanService duplicateScanService) {
        return new DuplicateController(duplicateService, duplicateScanService);
    }

    @Bean
    @ConditionalOnPluginEnabled("duplicate")
    public DuplicateHashBackfillTask duplicateHashBackfillTask(ImageHashMapper imageHashMapper,
                                                               ArtworkHashService artworkHashService,
                                                               DuplicateService duplicateService,
                                                               PixivDatabase pixivDatabase,
                                                               AppMessages messages) {
        return new DuplicateHashBackfillTask(
                imageHashMapper, artworkHashService, duplicateService, pixivDatabase, messages);
    }
}
