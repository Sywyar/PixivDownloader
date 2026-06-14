package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 包依赖守卫。后续每个解耦步骤完成后，把对应的「禁止依赖」追加固化到这里，防止回潮。
 */
class PluginApiDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    /**
     * 业务插件禁止直接调用的 {@code java.nio.file.Files} 读取 / 打开类静态方法。作品文件（含 meta sidecar）的
     * 枚举 / 读取是核心本地资产能力，只能经 {@code WorkAssetService}（sidecar 经 {@code findSidecarMeta}）；
     * 业务插件直读本地文件就有自行拼 {@code {workId}.meta.json} 绕过桥的回潮风险。
     */
    private static final Set<String> FORBIDDEN_FILE_READ_METHODS = Set.of(
            "readString", "readAllBytes", "readAllLines", "lines",
            "newBufferedReader", "newInputStream");

    /** target owner 是 {@code java.nio.file.Files} 且方法名落在 {@link #FORBIDDEN_FILE_READ_METHODS} 内的方法调用。 */
    private static final DescribedPredicate<JavaMethodCall> READS_LOCAL_FILES_DIRECTLY =
            new DescribedPredicate<>("调用 java.nio.file.Files 的本地文件读取 / 打开方法") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().getFullName().equals("java.nio.file.Files")
                            && FORBIDDEN_FILE_READ_METHODS.contains(call.getName());
                }
            };

    @Test
    @DisplayName("plugin.api 必须自包含：只依赖 JDK、jakarta.servlet 与 plugin.api 自身")
    void pluginApiIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api..", "java..",
                        "jakarta.servlet..")
                .because("plugin.api 是跨插件边界共享的契约包，不得依赖任何业务包或框架；"
                        + "jakarta.servlet 是 Servlet 规范 API，仅服务接口签名允许使用（见下一条规则）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin.api 中除服务接口外保持纯 JDK：contribution record 不得携带 servlet 类型")
    void pluginApiDataTypesStayPureJdk() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api..")
                .and().doNotHaveFullyQualifiedName(
                        top.sywyar.pixivdownload.plugin.api.work.service.WorkVisibilityService.class.getName())
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api..", "java..")
                .because("jakarta.servlet 的放行仅限收请求入参的服务接口（当前唯 WorkVisibilityService），"
                        + "纯数据的 contribution / record / 事件类型必须保持零依赖")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 stats 插件包（组合根 BuiltInPlugins 除外）")
    void coreDoesNotDependOnStatsPlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.stats..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.stats..")
                .because("stats 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "BuiltInPlugins 是既定的组合根例外")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 duplicate 插件包（组合根 BuiltInPlugins 与下载即时算 Hash 链路除外）")
    void coreDoesNotDependOnDuplicatePlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.duplicate..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .and().doNotHaveFullyQualifiedName("top.sywyar.pixivdownload.download.DownloadService")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .because("duplicate 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "BuiltInPlugins 是既定的组合根例外，DownloadService→ImageHashService "
                        + "是『下载后即时算 Hash』的既定核心链路例外（不随插件禁用）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载即时算 Hash 链路例外仅限 ImageHashService 一个类")
    void downloadServiceOnlyTouchesImageHashService() {
        noClasses()
                .that().haveFullyQualifiedName("top.sywyar.pixivdownload.download.DownloadService")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                                .and(DescribedPredicate.not(JavaClass.Predicates.type(
                                        top.sywyar.pixivdownload.duplicate.ImageHashService.class))))
                .because("核心链路例外的口径收窄到 Hash 计算入口本身，防止经例外类扩散依赖")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery 插件包不得依赖核心实现类：数据与文件访问只能走 plugin.api 核心接口")
    void galleryDependsOnlyOnCoreInterfaces() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.download.DownloadService.class,
                        top.sywyar.pixivdownload.core.db.PixivDatabase.class,
                        top.sywyar.pixivdownload.download.ArtworkFileLocator.class,
                        top.sywyar.pixivdownload.author.AuthorService.class,
                        top.sywyar.pixivdownload.series.MangaSeriesService.class))
                .because("画廊已接口化：查询走 WorkQueryService/WorkMetadataRepository、删除走 "
                        + "WorkAssetService/WorkDeletionService，禁止回潮直连核心实现类")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 gallery 插件包（组合根 BuiltInPlugins 除外）")
    void coreDoesNotDependOnGalleryPlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.gallery..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .because("gallery 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "插画 SQL 仓库（GalleryRepository / GalleryQuery / GuestRestriction）已收编进核心"
                        + "数据层 core.metadata，BuiltInPlugins 是既定的组合根例外")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 novel.db 包（novel 包内、tts.narration 与组合根 CorePlugin 除外）")
    void coreDoesNotDependOnNovelDbPackage() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.novel..")
                .and().resideOutsideOfPackage("top.sywyar.pixivdownload.tts.narration..")
                .and().doNotHaveFullyQualifiedName("top.sywyar.pixivdownload.plugin.CorePlugin")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel.db..")
                .because("小说画廊查询面（NovelGalleryRepository / NovelMetadataRepository / 行 / 系列 / 标签 DTO）"
                        + "已收编进核心数据层 core.metadata，核心查询 / 资产 / 收藏 / 计划 / 访客可见性链路不得再反向 "
                        + "import novel.db；novel-core 自身、AI 听书子系统 tts.narration（随 novel 插件整体拆分）与 "
                        + "schema 组合根 CorePlugin（声明 NovelSchemaContribution）是既定例外")
                .check(CLASSES);
    }

    @Test
    @DisplayName("小说画廊侧服务不得依赖核心实现类：数据与文件访问只能走 plugin.api 核心接口")
    void novelGalleryServicesDependOnlyOnCoreInterfaces() {
        // novel 包同时容纳 novel-core（下载/正文/翻译/TTS，不强拆、合法直连 NovelDatabase），
        // 守卫范围因此按 Bean 收敛口径限定在小说画廊侧两个接口化服务，而非整个包
        noClasses()
                .that(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.novel.NovelGalleryService.class,
                        top.sywyar.pixivdownload.novel.NovelBatchService.class))
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.novel.db.NovelDatabase.class,
                        top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository.class,
                        top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class,
                        top.sywyar.pixivdownload.author.AuthorService.class,
                        top.sywyar.pixivdownload.core.appconfig.DownloadConfig.class))
                .because("小说画廊已接口化：查询走 WorkQueryService/WorkMetadataRepository、删除走 "
                        + "WorkAssetService/WorkDeletionService，禁止回潮直连核心实现类；小说 upload_time "
                        + "列也藏在核心 NovelMetadataRepository 后，只能经 WorkMetadataRepository 读")
                .check(CLASSES);
    }

    @Test
    @DisplayName("业务插件不得直读 meta sidecar 实现层：sidecar 只能经 WorkAssetService.findSidecarMeta 读")
    void businessPluginsMustNotReadMetaSidecarDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .or(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.novel.NovelGalleryService.class,
                        top.sywyar.pixivdownload.novel.NovelBatchService.class))
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download.meta..")
                .because("作品 meta sidecar 的归一化 / 落盘 / 文件层读写是核心捕获实现（download.meta）；"
                        + "画廊与小说画廊两侧业务插件取 sidecar 只能经 plugin.api 的 "
                        + "WorkAssetService.findSidecarMeta（产出 JDK-only WorkSidecarMeta），"
                        + "禁止直依赖 download.meta 实现层或自行解析 {workId}.meta.json")
                .check(CLASSES);
    }

    @Test
    @DisplayName("业务插件不得直接调用 java.nio.file.Files 读本地文件：作品文件枚举 / 读取只能经 WorkAssetService")
    void businessPluginsMustNotReadFilesDirectly() {
        // 字节码级补强：上一条只能拦「依赖 download.meta 实现包」，拦不到业务插件手写
        // Files.readString(dir.resolve(id + ".meta.json")) 这类直读本地文件名绕过桥的回潮。
        // Spring AOP 拦不到 java.nio.file.Files 的 static 方法、也只覆盖跑过的路径，故用 ArchUnit 静态守卫。
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .or(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.novel.NovelGalleryService.class,
                        top.sywyar.pixivdownload.novel.NovelBatchService.class))
                .should().callMethodWhere(READS_LOCAL_FILES_DIRECTLY)
                .because("作品文件（含 meta sidecar）的枚举 / 读取是核心本地资产能力：sidecar 只能经 "
                        + "WorkAssetService.findSidecarMeta 读、普通作品文件也应经 WorkAssetService，"
                        + "业务插件不得直接调用 java.nio.file.Files 读本地文件，更不得自行拼 {workId}.meta.json 绕过桥")
                .check(CLASSES);
    }

    @Test
    @DisplayName("common 不得依赖业务包：项目内仅允许 common/config/i18n")
    void commonDependsOnlyOnInfrastructure() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.common..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("top.sywyar.pixivdownload..")
                                .and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(
                                        "top.sywyar.pixivdownload.common..",
                                        "top.sywyar.pixivdownload.config..",
                                        "top.sywyar.pixivdownload.i18n.."))))
                .because("common 是叶子工具包，只允许依赖 config / i18n 两个基础设施包")
                .check(CLASSES);
    }
}
