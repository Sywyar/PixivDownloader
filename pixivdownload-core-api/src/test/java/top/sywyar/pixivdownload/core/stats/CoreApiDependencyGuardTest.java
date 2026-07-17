package top.sywyar.pixivdownload.core.stats;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.DebugSettings;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRules;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.archive.ArchiveWorkDeletion;
import top.sywyar.pixivdownload.core.collection.ArtworkCollectionMembership;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.gallery.GalleryDataProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.model.GallerySourceDescriptor;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCountResult;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryRuntimeQuery;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryRuntimeSnapshot;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryWorkResult;
import top.sywyar.pixivdownload.core.hash.ArtworkHashEntry;
import top.sywyar.pixivdownload.core.hash.ArtworkHashFingerprint;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexQuery;
import top.sywyar.pixivdownload.core.metadata.novel.NovelRecord;
import top.sywyar.pixivdownload.core.metadata.novel.NovelSeries;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSeverity;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.web.LocalRequestTrust;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * core-api 边界守卫：证明本模块只承载核心 owned 的中性语义端口与纯 JDK 值类型，保持 Spring-free，
 * 并与 {@code plugin.api} 的框架洁净守卫正交。
 *
 * <p>本守卫在 {@code pixivdownload-core-api} 模块内自包含运行：{@link ClassFileImporter} 只扫描本模块 main
 * classpath 上的 {@code top.sywyar.pixivdownload..} 类（app / 插件类不在本模块 classpath 上），与主程序的
 * {@code PluginApiDependencyGuardTest} 正交、各自从自己模块的 classpath 断言。
 */
class CoreApiDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("core-api 必须自包含：只依赖 JDK 与本模块纯契约包")
    void coreApiIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.ai..",
                        "top.sywyar.pixivdownload.config..",
                        "top.sywyar.pixivdownload.core.archive..",
                        "top.sywyar.pixivdownload.core.collection..",
                        "top.sywyar.pixivdownload.core.db.pathprefix..",
                        "top.sywyar.pixivdownload.core.gallery..",
                        "top.sywyar.pixivdownload.core.hash..",
                        "top.sywyar.pixivdownload.core.metadata.novel..",
                        "top.sywyar.pixivdownload.core.stats..",
                        "top.sywyar.pixivdownload.core.web..",
                        "top.sywyar.pixivdownload.i18n..",
                        "top.sywyar.pixivdownload.notification..",
                        "top.sywyar.pixivdownload.push..",
                        "top.sywyar.pixivdownload.setup..",
                        "top.sywyar.pixivdownload.tts.narration.engine..",
                        "top.sywyar.pixivdownload.web..",
                        "java..")
                .because("core-api 是 Spring-free 纯 JDK 的核心 owned 语义端口与可选能力契约模块："
                        + "只能依赖 JDK 与本模块纯契约包，不得依赖 Spring / SLF4J / JDBC / MyBatis、"
                        + "core.stats.db 实现层或任何 app 业务实现包；将来若某端口确需共享类型，"
                        + "只能 +plugin-api 且须先在 PLAN 记录、不在功能任务中主动引入")
                .check(CLASSES);
    }

    @Test
    @DisplayName("core-api 不得依赖 Spring / Apache / SLF4J / JDBC / MyBatis 或实现层")
    void coreApiHasNoFrameworkOrImplDependency() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.apache..",
                        "org.slf4j..", "ch.qos.logback..",
                        "java.sql..", "javax.sql..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("core-api 只承载核心 owned 的中性语义端口与纯 JDK 值类型：Spring、Apache HTTP、"
                        + "日志门面、JDBC、MyBatis 与 mapper/repository 实现全部留在 app 实现层，"
                        + "core-api 不得反向依赖它们")
                .check(CLASSES);
    }

    @Test
    @DisplayName("notification 场景模型不得依赖 push 包")
    void notificationScenarioDoesNotDependOnPushPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.notification..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.push..")
                .because("通知场景与严重程度由 notification 中性命名空间拥有，push 只能在自身边界做介质映射")
                .check(CLASSES);
    }

    @Test
    @DisplayName("NotificationScenario.level 返回中性 NotificationSeverity")
    void notificationScenarioLevelReturnsNeutralSeverity() throws NoSuchMethodException {
        assertThat(NotificationScenario.class.getMethod("level").getReturnType())
                .isEqualTo(NotificationSeverity.class);
    }

    @Test
    @DisplayName("core-api 模块应包含 StatsQueryStore 与 StatsAggregates（防守卫 vacuous 通过）")
    void coreApiContainsStatsApiTypes() {
        assertThat(CLASSES.contain(StatsQueryStore.class.getName())).isTrue();
        assertThat(CLASSES.contain(StatsAggregates.class.getName())).isTrue();
    }

    @Test
    @DisplayName("core-api 模块应包含中性画廊 provider、只读运行时门面与纯值结果")
    void coreApiContainsGalleryApiTypes() {
        assertThat(CLASSES.contain(GalleryDataProvider.class.getName())).isTrue();
        assertThat(CLASSES.contain(GallerySourceDescriptor.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryFrontendContribution.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryFrontendProvider.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryRuntimeQuery.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryRuntimeSnapshot.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryCountResult.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryWorkResult.class.getName())).isTrue();
    }

    @Test
    @DisplayName("core-api 模块应包含核心图片哈希索引端口与纯值投影")
    void coreApiContainsArtworkHashIndexContracts() {
        assertThat(CLASSES.contain(ArtworkHashIndexQuery.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArtworkHashIndexMaintenance.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArtworkHashEntry.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArtworkHashFingerprint.class.getName())).isTrue();
    }

    @Test
    @DisplayName("core-api 模块应包含小说元数据纯值模型")
    void coreApiContainsNovelMetadataValues() {
        assertThat(CLASSES.contain(NovelRecord.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelSeries.class.getName())).isTrue();
    }

    @Test
    @DisplayName("core-api 模块应包含归档导出与作品收藏成员端口")
    void coreApiContainsArchiveAndCollectionContracts() {
        assertThat(CLASSES.contain(ArchiveExportRules.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArchiveExportEntry.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArchiveExportRequest.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArchiveExportResult.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArchiveExportService.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArchiveWorkDeletion.class.getName())).isTrue();
        assertThat(CLASSES.contain(ArtworkCollectionMembership.class.getName())).isTrue();
    }

    @Test
    @DisplayName("core-api 模块应包含宿主配置、路径、代理与请求解析契约")
    void coreApiContainsHostRuntimeContracts() {
        assertThat(CLASSES.contain(DownloadSettings.class.getName())).isTrue();
        assertThat(CLASSES.contain(MultiModeSettings.class.getName())).isTrue();
        assertThat(CLASSES.contain(DebugSettings.class.getName())).isTrue();
        assertThat(CLASSES.contain(RuntimePathProvider.class.getName())).isTrue();
        assertThat(CLASSES.contain(StoredPathCodec.class.getName())).isTrue();
        assertThat(CLASSES.contain(OutboundProxySettings.class.getName())).isTrue();
        assertThat(CLASSES.contain(OutboundProxyEndpoint.class.getName())).isTrue();
        assertThat(CLASSES.contain(OutboundProxyOverride.class.getName())).isTrue();
        assertThat(CLASSES.contain(AcquisitionCredentialResolver.class.getName())).isTrue();
        assertThat(CLASSES.contain(ApplicationModeProvider.class.getName())).isTrue();
        assertThat(CLASSES.contain(LocalRequestTrust.class.getName())).isTrue();
    }

    @Test
    @DisplayName("运行模式端口只暴露只读的 getMode 契约")
    void applicationModeProviderIsMinimalAndReadOnly() {
        assertThat(ApplicationModeProvider.class.getDeclaredMethods())
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("getMode");
                    assertThat(method.getReturnType()).isEqualTo(String.class);
                    assertThat(method.getParameterCount()).isZero();
                });
    }
}
