package top.sywyar.pixivdownload.novelgallery;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.NovelPf4jPlugin;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.novel.NovelPluginConfiguration;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.schedule.PixivScheduledNovelWorkExecutor;
import top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
    @DisplayName("novel-gallery 通用查询走中性接口且仅正文适配层可读插件数据库")
    void novelGalleryServicesKeepPrivateContentAccessInOwnedAdapter() {
        Set<String> forbiddenTypes = Set.of(
                NovelDatabase.class.getName(),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelGalleryRepository"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRepository"),
                hostType("top.sywyar.pixivdownload.author", "AuthorService"));
        noClasses()
                .that(JavaClass.Predicates.belongToAnyOf(
                        NovelGalleryService.class,
                        NovelBatchService.class))
                .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "宿主元数据实现或插件正文数据库",
                        javaClass -> forbiddenTypes.contains(javaClass.getName())))
                .because("novel-gallery 的通用元数据查询走 WorkQueryService/WorkMetadataRepository，"
                        + "删除走 WorkDeletionService，普通文件枚举走 WorkAssetService")
                .check(CLASSES);
    }

    @Test
    @DisplayName("小说删除只能走宿主统一作品删除入口")
    void novelPersistenceDoesNotExposeDeletionBypass() {
        assertThat(Arrays.stream(NovelDatabase.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("deleteNovel", "markNovelDeleted");

        for (Method method : NovelMapper.class.getDeclaredMethods()) {
            Delete delete = method.getAnnotation(Delete.class);
            if (delete == null) {
                continue;
            }
            assertThat(delete.value())
                    .as(method.getName() + " 不得直接删除 novels 主行")
                    .noneMatch(sql -> sql.matches("(?is).*\\bDELETE\\s+FROM\\s+novels\\b.*"));
        }
    }

    @Test
    @DisplayName("novel 生产代码只能依赖宿主稳定端口，不得依赖配置、模式、路径、身份与可见性实现")
    void novelModuleDoesNotDependOnHostImplementations() {
        Set<String> forbiddenTypes = Set.of(
                hostType("top.sywyar.pixivdownload.core.appconfig", "DownloadConfig"),
                hostType("top.sywyar.pixivdownload.core.appconfig", "MultiModeConfig"),
                hostType("top.sywyar.pixivdownload.config", "DebugConfig"),
                hostType("top.sywyar.pixivdownload.config", "RuntimeFiles"),
                hostType("top.sywyar.pixivdownload.common", "SafePathSegment"),
                hostType("top.sywyar.pixivdownload.core.db", "PixivDatabase"),
                hostType("top.sywyar.pixivdownload.core.db.pathprefix", "PathPrefixCodec"),
                hostType("top.sywyar.pixivdownload.core.db.schema", "DatabaseInitializer"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRepository"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRow"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelSeriesTitleRow"),
                hostType("top.sywyar.pixivdownload.i18n", "LocalizedException"),
                hostType("top.sywyar.pixivdownload.i18n", "MessageBundles"),
                hostType("top.sywyar.pixivdownload.author", "AuthorService"),
                hostType("top.sywyar.pixivdownload.collection", "CollectionService"),
                hostType("top.sywyar.pixivdownload.quota", "UserQuotaService"),
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

    @Test
    @DisplayName("novel 计划作品执行只走 plugin-api 契约并随插件生命周期托管")
    void novelScheduleUsesOnlyPluginApiWorkExecutors() {
        noClasses()
                .that().resideInAnyPackage(
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.core.schedule.work..")
                .because("小说计划作品已由 plugin-api ScheduledWorkExecutor 执行，"
                        + "不得恢复 app legacy schedule 载体或 runner")
                .check(CLASSES);

        assertThat(CLASSES.contain(PixivScheduledNovelWorkExecutor.class.getName())).isTrue();
        classes()
                .that().areAssignableTo(ScheduledWorkExecutor.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("小说计划作品执行器必须由 child context 显式装配并随插件生命周期撤回")
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
        assertThat(CLASSES.contain(NovelOwnedWorkSearch.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelBatchService.class.getName())).isTrue();
    }
}
