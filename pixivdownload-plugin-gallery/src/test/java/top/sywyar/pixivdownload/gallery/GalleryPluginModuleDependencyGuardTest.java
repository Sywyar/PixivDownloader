package top.sywyar.pixivdownload.gallery;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery 模块边界守卫")
class GalleryPluginModuleDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.gallery");

    @Test
    @DisplayName("gallery 托管 Bean 不得直连数据库底层")
    void galleryManagedBeansDoNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("gallery 读取核心作品事实只能经 core owned repository/service 与 plugin.api 契约，"
                        + "不得自建 JDBC/MyBatis 访问核心表")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery 不得回潮依赖旧下载 / 文件定位 / 作者系列实现类")
    void galleryDependsOnlyOnNeutralCoreServices() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.core.download.ArtworkFileService.class,
                        top.sywyar.pixivdownload.core.download.DownloadedArtworkService.class,
                        top.sywyar.pixivdownload.core.download.ArtworkMetadataRecoveryService.class,
                        top.sywyar.pixivdownload.core.db.PixivDatabase.class,
                        top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator.class,
                        top.sywyar.pixivdownload.author.AuthorService.class,
                        top.sywyar.pixivdownload.series.MangaSeriesService.class,
                        top.sywyar.pixivdownload.core.db.PixivMapper.class,
                        top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper.class))
                .because("gallery 已接口化：查询走 WorkQueryService/WorkMetadataRepository，删除走 "
                        + "WorkAssetService/WorkDeletionService，文件访问与元数据事实留在 core owned 服务内")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery 模块不依赖 duplicate / download-workbench / stats / novel 插件实现")
    void galleryModuleDoesNotDependOnOtherFeatureImplementations() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.duplicate..",
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.stats..",
                        "top.sywyar.pixivdownload.novel..")
                .because("gallery 只消费核心作品事实与插件契约，不应依赖其它功能插件实现")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery 模块应包含插件本体、PF4J 主类、配置类和业务 Bean（防空跑）")
    void galleryModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(GalleryPf4jPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryPluginConfiguration.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryController.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryService.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryBatchService.class.getName())).isTrue();
    }
}
