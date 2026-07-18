package top.sywyar.pixivdownload.novelgallery;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.NovelPf4jPlugin;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.novel.NovelPluginConfiguration;
import top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("novel 模块边界守卫")
class NovelGalleryPluginModuleDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.novel", "top.sywyar.pixivdownload.novelgallery");

    @Test
    @DisplayName("novel-gallery 托管 Bean 不得直连数据库底层或核心 mapper")
    void novelGalleryManagedBeansDoNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("novel-gallery 只能经小说核心服务与 plugin.api 契约读取展示数据，"
                        + "不得自建 JDBC/MyBatis 访问核心表")
                .check(CLASSES);
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.novel.db.NovelMapper.class))
                .because("novel-gallery 不得直接依赖小说核心 mapper")
                .check(CLASSES);
    }

    @Test
    @DisplayName("novel-gallery 查询与批量服务只能走中性作品接口")
    void novelGalleryServicesDependOnlyOnNeutralCoreServices() {
        noClasses()
                .that(JavaClass.Predicates.belongToAnyOf(
                        NovelGalleryService.class,
                        NovelBatchService.class))
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.novel.db.NovelDatabase.class,
                        top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository.class,
                        top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class,
                        top.sywyar.pixivdownload.author.AuthorService.class))
                .because("novel-gallery 的列表 / 批量服务已接口化：查询走 WorkQueryService/WorkMetadataRepository，"
                        + "删除走 WorkDeletionService，普通文件枚举走 WorkAssetService")
                .check(CLASSES);
    }

    @Test
    @DisplayName("novel 生产代码只能依赖宿主稳定端口，不得依赖配置、模式、路径、身份与可见性实现")
    void novelModuleDoesNotDependOnHostImplementations() {
        Set<String> forbiddenTypes = Set.of(
                hostType("top.sywyar.pixivdownload.core.appconfig", "DownloadConfig"),
                hostType("top.sywyar.pixivdownload.core.appconfig", "MultiModeConfig"),
                hostType("top.sywyar.pixivdownload.config", "DebugConfig"),
                hostType("top.sywyar.pixivdownload.config", "RuntimeFiles"),
                hostType("top.sywyar.pixivdownload.core.db.pathprefix", "PathPrefixCodec"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRepository"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRow"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelSeriesMetadataRow"),
                hostType("top.sywyar.pixivdownload.setup", "SetupService"),
                hostType("top.sywyar.pixivdownload.setup.guest", "GuestAccessGuard"),
                hostType("top.sywyar.pixivdownload.setup.guest", "GuestInviteSession"),
                hostType("top.sywyar.pixivdownload.common", "UuidUtils"));
        noClasses()
                .that().resideInAnyPackage(
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "宿主实现类型",
                        javaClass -> forbiddenTypes.contains(javaClass.getName())))
                .because("外置 novel 插件应依赖 core-api/plugin-api 稳定端口，"
                        + "宿主配置绑定、运行期路径、setup、访客会话与 UUID 解析实现必须留在 app")
                .check(CLASSES);
    }

    private static String hostType(String packageName, String simpleName) {
        return packageName + "." + simpleName;
    }

    @Test
    @DisplayName("novel-gallery 不依赖 AI/TTS/download-workbench/gallery/duplicate/stats 插件实现")
    void novelGalleryDoesNotDependOnOtherFeatureImplementations() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.ai..",
                        "top.sywyar.pixivdownload.tts..",
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.gallery..",
                        "top.sywyar.pixivdownload.duplicate..",
                        "top.sywyar.pixivdownload.stats..")
                .because("novel-gallery 只消费小说核心服务、核心作品事实与插件契约，"
                        + "AI/TTS 等能力通过 UI slot / 能力缺席语义协作")
                .check(CLASSES);
    }

    @Test
    @DisplayName("novel 模块应包含插件本体、PF4J 主类、配置类和业务 Bean（防空跑）")
    void novelGalleryModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(NovelPf4jPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelPluginConfiguration.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelGalleryController.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelGalleryService.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelBatchService.class.getName())).isTrue();
    }
}
