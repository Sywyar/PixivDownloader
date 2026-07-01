package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

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
    @DisplayName("plugin.api.gui 契约只能依赖 JDK 与 plugin-api 自身")
    void pluginApiGuiThemeContractIsPureJdk() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api.gui..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api..", "java..")
                .because("GUI contribution 是跨边界契约：只能依赖 JDK 与 plugin-api 自身，"
                        + "不得引入 PF4J / Spring / FlatLaf / JNA 或 app 业务类型")
                .check(CLASSES);
    }

    @Test
    @DisplayName("app 生产代码不得 import PF4J 类型")
    void appProductionCodeDoesNotImportPf4j() throws IOException {
        Path sourceRoot = Path.of("pixivdownload-app/src/main/java");
        if (!Files.exists(sourceRoot)) {
            sourceRoot = Path.of("src/main/java");
        }
        try (var paths = Files.walk(sourceRoot)) {
            assertThat(paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "import org.pf4j"))
                    .map(Path::toString)
                    .toList())
                    .isEmpty();
        }
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
    @DisplayName("核心不得反向依赖 duplicate 插件包（组合根 BuiltInPlugins 除外）")
    void coreDoesNotDependOnDuplicatePlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.duplicate..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .because("duplicate 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "BuiltInPlugins 是既定的组合根例外。『下载后即时算 Hash』已抽成核心服务 "
                        + "core.hash.ArtworkHashService（ArtworkDownloadExecutor 注入核心服务、不再依赖 duplicate 包），"
                        + "故不再有 ArtworkDownloadExecutor→duplicate 的核心链路例外")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心 Hash 写入服务 ArtworkHashService 是核心 Bean：不得标 @PluginManagedBean")
    void artworkHashServiceIsCoreNotPluginManaged() {
        classes()
                .that().haveFullyQualifiedName("top.sywyar.pixivdownload.core.hash.ArtworkHashService")
                .should().notBeAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .because("下载后即时算 Hash 是核心资产索引链路，须由根包扫描装配的核心服务承载、"
                        + "不属任何功能插件、不随 plugins.<id>.enabled 缺席；标 @PluginManagedBean 会把它退回"
                        + "插件托管、重新引入『禁用 duplicate 却仍有插件托管 Bean 常驻』的归属歧义")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery 插件包不得依赖核心实现类：数据与文件访问只能走 plugin.api 核心接口")
    void galleryDependsOnlyOnCoreInterfaces() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.download.ArtworkDownloadExecutor.class,
                        top.sywyar.pixivdownload.download.ArtworkFileService.class,
                        top.sywyar.pixivdownload.download.DownloadedArtworkService.class,
                        top.sywyar.pixivdownload.download.ArtworkMetadataRecoveryService.class,
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
    @DisplayName("核心本地资产 serving（core.asset）不得依赖 download 包：图片字节只能经 WorkAssetService 出")
    void coreAssetServingDoesNotDependOnDownloadPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.core.asset..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download..")
                .because("『读已下载作品的本地图片字节』是核心本地资产能力，core.asset 的 serving 端点只能经 "
                        + "plugin.api 的 WorkAssetService 取文件，不得依赖将来要拆成下载工作台插件的 download 包；"
                        + "否则禁用下载工作台会让画廊 / 橱窗 / 系列 / 详情页裂图")
                .check(CLASSES);
    }

    @Test
    @DisplayName("计划任务宿主 schedule 引擎 Bean 不得依赖核心计划任务数据实现层：只能经 core.schedule 语义 Store/API 读写")
    void scheduleEngineBeansMustNotAccessCoreScheduleImplDirectly() {
        // scheduled_tasks / scheduled_task_pending 是核心 owned schema；其语义数据访问门面 ScheduledTaskStore
        // 已是核心 owned 接口（core.schedule），底层 MyBatis ScheduledTaskMapper / schema 初始化 / 数据库方言适配
        // 收口在核心实现层 core.schedule.db。计划任务宿主插件的引擎 Bean（ScheduleExecutor / ScheduleService /
        // ScheduleRunner / ScheduleController 等，均为 @PluginManagedBean）只能依赖 core.schedule 语义 Store/API 与
        // 行模型，不得直接依赖 core.schedule.db 实现层、池化 DataSource、JdbcTemplate、裸 Connection 或 MyBatis
        // 自由 SQL 访问核心表。
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "javax.sql..", "java.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..")
                .because("scheduled_tasks / scheduled_task_pending 是核心 owned schema；其语义数据访问门面 "
                        + "ScheduledTaskStore 是核心 owned 接口（core.schedule），底层 mapper / schema 初始化 / 方言适配 "
                        + "收口在核心实现层 core.schedule.db。计划任务宿主插件的 schedule 引擎 Bean 只能依赖 "
                        + "core.schedule 语义 Store/API 与行模型，不得直接依赖 core.schedule.db 实现层、ScheduledTaskMapper / "
                        + "DataSource / JdbcTemplate / Connection / MyBatis 自由 SQL 访问核心表")
                .check(CLASSES);
    }

    @Test
    @DisplayName("schedule 宿主不得依赖 novel 插件包：小说下载 / 系列合订 / 翻译状态只能经核心契约 ScheduledWorkRunner")
    void scheduleDoesNotDependOnNovelPlugin() {
        // 计划任务的小说一侧（构造 NovelDownloadRequest + downloadBlocking + 系列合订 + 队列视图翻译状态叠加）已收口
        // 到核心契约 core.schedule.work.ScheduledWorkRunner（按作品类型解析、由小说插件贡献小说执行器实现）。计划任务
        // 宿主（schedule 包：执行器 / 服务 / tick runner / 控制器 / 运行状态 / 运行队列 / 过度访问告警）只依赖该核心接口
        // 与中性载体，不得 import 任何 novel 包类型——限流 / 熔断 / 代理 / 运行队列 / 水位线等共享调度机器留调度壳，来源
        // 执行契约住下载工作台域（download.schedule.source），小说插件经正常 plugin→core 方向实现契约，故无
        // schedule↔novel 互相 import。
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("计划任务的小说下载 / 系列合订 / 翻译状态经核心契约 core.schedule.work.ScheduledWorkRunner"
                        + "（小说插件贡献执行器实现）完成，调度编排层不得 import 任何 novel 包类型；小说插件经 plugin→core 正向"
                        + "依赖实现该契约，发现 / 筛选 / 系列补全 / 共享调度机器仍留调度壳")
                .check(CLASSES);
    }

    @Test
    @DisplayName("作品类型执行器必须 @PluginManagedBean（不得根包扫描）：随贡献它的插件生命周期归属")
    void scheduledWorkRunnersMustBePluginManaged() {
        // ScheduledNovelDownloadDelegate 这类 ScheduledWorkRunner 实现（小说执行器住 novel 包、插画执行器住 schedule
        // 包）必须标 @PluginManagedBean、由各自 XxxPluginConfiguration 显式装配、排除出根包扫描——否则贡献它的插件被禁 /
        // 卸载后，根扫描的 @Service 仍会注册执行器偷跑，破坏「缺执行器即该作品类型不可用」语义。接口本身不在约束面。
        classes()
                .that().areAssignableTo(
                        top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("作品类型执行器随贡献它的插件生命周期归属：必须 @PluginManagedBean 由对应 "
                        + "XxxPluginConfiguration 显式装配、排除出根包扫描，不得用 @Service / @Component 根扫描注册，"
                        + "否则插件禁用后仍被注册偷跑")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载工作台计划任务来源 / 执行器不得依赖 novel 包：发现与下载只经核心契约 + 中性载体")
    void downloadScheduleSourcesAndRunnerDoNotDependOnNovel() {
        // 计划任务来源（download.schedule.source，怎么找作品）与插画作品类型执行器（download.schedule.work）由下载
        // 工作台贡献给计划任务宿主。它们随 schedule 能力拆出宿主插件后离开了 schedule.. 包，但「计划任务逻辑对 novel
        // 保持解耦」的不变量仍须守住：来源经 PixivFetchService + 中性载体发现插画 / 小说作品（不 import 任何 novel
        // 类型），插画执行器只薄包核心窄接缝 ArtworkDownloader；小说下载由小说插件贡献的执行器经核心契约
        // core.schedule.work.ScheduledWorkRunner 按 kind 解析完成。
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.download.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("计划任务来源 / 插画执行器经 PixivFetchService + 中性载体 + 核心契约 ScheduledWorkRunner "
                        + "工作，不得 import 任何 novel 包类型；小说下载由小说插件贡献的执行器按 kind 解析完成")
                .check(CLASSES);
    }

    @Test
    @DisplayName("插件托管业务 Bean 不得直连数据库底层：JDBC / MyBatis 与核心 DB 实现层只能经核心语义 Store/API")
    void pluginManagedBeansMustNotAccessRawDatabaseDirectly() {
        // 「@PluginManagedBean」精确等于「插件 Configuration 经 @Bean 显式装配、排除出根包扫描的那一组业务 Bean」——
        // 该标记正是把类排除出根包扫描、改由 XxxPluginConfiguration 装配的机制；唯一不带该标记的被装配类是各插件的
        // XxxPlugin 描述类（纯 contribution 声明、不碰数据）。这组 Bean 对核心数据的访问必须经核心语义 Store/API
        // （如 core.schedule.ScheduledTaskStore / core.stats.StatsQueryStore），不得绕过去直碰数据库底层：
        //   · 禁 java.sql / javax.sql / Spring JDBC / MyBatis 运行期类型（自建 JdbcTemplate、注入池化 DataSource、
        //     裸 Connection、自由 SQL）——这一组正是 stats 历史直连（StatsRepository 自建 NamedParameterJdbcTemplate）
        //     与 schedule 历史直访 mapper 的回潮形态；
        //   · 禁核心 DB 实现层 core.schedule.db.. / core.stats.db..（语义 Store 的 @Repository 实现 + 其内部 mapper）；
        //   · 禁核心表 MyBatis mapper（PixivMapper / PathPrefixMapper，住混合包 core.db 故按类点名而非按包）。
        // 不在禁用面内（合法 plugin→core 正向依赖，不得误伤）：core.db 行模型（ArtworkRecord 等）、核心服务
        // PixivDatabase、novel 插件自有数据域 mapper（NovelMapper，各自数据域、非核心主库）、核心图片哈希数据域
        // mapper（core.hash.ImageHashMapper，下载写入服务 ArtworkHashService 与重复检测 UI/扫描/回填共享、
        // 非核心主库 mapper，故不点名进禁用面）、core.schedule / core.stats 语义接口本身。
        // 核心实现层（ScheduledTaskStoreImpl / StatsQueryStoreImpl / PixivDatabase / 核心 @Service ArtworkHashService 等，
        // 均非 @PluginManagedBean）允许自由使用 JDBC / MyBatis / 事务模板——本守卫只约束 @PluginManagedBean，故不误伤核心实现。
        noClasses()
                .that().areAnnotatedWith(top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAnyPackage(
                                        "java.sql..", "javax.sql..",
                                        "org.springframework.jdbc..",
                                        "org.apache.ibatis..",
                                        "top.sywyar.pixivdownload.core.schedule.db..",
                                        "top.sywyar.pixivdownload.core.stats.db..")
                                .or(JavaClass.Predicates.belongToAnyOf(
                                        top.sywyar.pixivdownload.core.db.PixivMapper.class,
                                        top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper.class)))
                .because("插件托管业务 Bean 对核心表的访问必须经核心语义 Store/API（ScheduledTaskStore / "
                        + "StatsQueryStore 等），不得自建 JdbcTemplate / 注入池化 DataSource / 直依赖 MyBatis mapper "
                        + "或核心 DB 实现层做自由 SQL；核心实现层（@Repository，非 @PluginManagedBean）不受此约束")
                .check(CLASSES);
    }

    @Test
    @DisplayName("必选插件的业务 Bean 不得标 @ConditionalOnPluginEnabled（method 级与 class 级都覆盖）：plugin-runtime 删 isRequired 短路分支后由本守卫固化")
    void requiredPluginBeansMustNotBeConditionalOnPluginEnabled() {
        // plugin-runtime 的 OnPluginEnabledCondition 已断掉对组合根 BuiltInPlugins 的反向引用（删除 isRequired
        // 短路分支、只读 plugins.<id>.enabled 开关）。该分支可安全删除的前提是「没有任何必选插件的业务 Bean 被
        // @ConditionalOnPluginEnabled 门控」——否则必选插件会被开关误伤。@ConditionalOnPluginEnabled 的 @Target
        // 同时含 METHOD 与 TYPE（既可标 @Bean 工厂方法，也可标整个 @Configuration 类一并门控其全部 @Bean），故本守卫
        // 两端都扫：method 级与 class 级被注解的元素都读 value()、断言被门控 id 都不是必选插件。生产中本注解当前只用
        // 于可选插件的 @Bean 工厂方法（gallery / novel / stats / duplicate）；必选插件（core / download-workbench /
        // schedule）的 Bean 恒无条件装配。class 级当前无生产用法（allowEmptyShould 放行空集），是防未来有人用类级注解
        // 门控必选插件的前向守卫。
        ArchCondition<JavaMethod> methodNotGateRequiredPlugin =
                new ArchCondition<>("@Bean 方法不得用 @ConditionalOnPluginEnabled 门控必选插件") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        String pluginId = method.getAnnotationOfType(ConditionalOnPluginEnabled.class).value();
                        if (BuiltInPlugins.isRequired(pluginId)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " 用 @ConditionalOnPluginEnabled(\"" + pluginId
                                            + "\") 门控了必选插件——必选插件不可禁用、其业务 Bean 必须恒无条件装配"));
                        }
                    }
                };
        ArchCondition<JavaClass> typeNotGateRequiredPlugin =
                new ArchCondition<>("@Configuration 类不得用 @ConditionalOnPluginEnabled 门控必选插件") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        String pluginId = clazz.getAnnotationOfType(ConditionalOnPluginEnabled.class).value();
                        if (BuiltInPlugins.isRequired(pluginId)) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getFullName() + " 用 @ConditionalOnPluginEnabled(\"" + pluginId
                                            + "\") 门控了必选插件——必选插件不可禁用、其托管 Bean 必须恒无条件装配"));
                        }
                    }
                };
        String because = "OnPluginEnabledCondition 已删除 isRequired 短路分支（plugin-runtime 不再回指组合根 "
                + "BuiltInPlugins）：删除安全的前提是『没有任何必选插件的 Bean 被 @ConditionalOnPluginEnabled "
                + "门控』。本守卫固化此不变量——必选插件（core / download-workbench / schedule）的业务 Bean 一律"
                + "不标本注解（method 级或 class 级都不行）、恒无条件装配；只有可选插件（gallery / novel / stats / "
                + "duplicate）才用它按开关装配 / 缺席";
        methods()
                .that().areAnnotatedWith(ConditionalOnPluginEnabled.class)
                .should(methodNotGateRequiredPlugin)
                .because(because)
                .check(CLASSES);
        // @Target 含 TYPE，但当前生产无类级用法 → allowEmptyShould 放行空集（前向守卫）。
        classes()
                .that().areAnnotatedWith(ConditionalOnPluginEnabled.class)
                .should(typeNotGateRequiredPlugin)
                .allowEmptyShould(true)
                .because(because)
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载队列控制器不得直接依赖具体作品类型下载服务：取消 / 清空只经核心队列宿主注册中心 QueueOperationRegistry")
    void downloadQueueControllerDoesNotDependOnConcreteDownloadServices() {
        // 跨类型 cancel / clear 已收口为核心队列宿主注册中心 QueueOperationRegistry（按 queueType 解析操作适配器）。
        // DownloadQueueController 只依赖该核心注册中心与中性 QueueOperations 契约，不得直接 import 任一具体作品类型
        // 下载实现——插画 ArtworkDownloadExecutor（同插件、但仍属具体实现）与小说 NovelDownloadService（跨插件反向耦合）
        // 都在禁用面内；插画 / 小说各经其 XxxPluginConfiguration 显式装配一个 QueueOperations 适配器贡献给注册中心。
        // 仅针对该控制器：同包其它下载控制器（DownloadStatusController / DownloadTaskController 依赖插画执行器、
        // PixivProxyController 依赖小说代理类型）是各自的合法路径、不在本守卫范围。
        noClasses()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.controller.DownloadQueueController")
                .should().dependOnClassesThat()
                .belongToAnyOf(
                        top.sywyar.pixivdownload.download.ArtworkDownloadExecutor.class,
                        top.sywyar.pixivdownload.novel.download.NovelDownloadService.class)
                .because("下载队列控制器的跨类型取消 / 清空经核心队列宿主注册中心 QueueOperationRegistry "
                        + "+ 中性契约 QueueOperations 多态派发，不得直接 import 插画 / 小说等具体下载实现")
                .check(CLASSES);
    }

    @Test
    @DisplayName("队列宿主操作适配器必须 @PluginManagedBean（不得根包扫描）：随贡献它的插件生命周期归属")
    void queueOperationsMustBePluginManaged() {
        // IllustQueueOperations（住 download 包、下载工作台贡献）/ NovelQueueOperations（住 novel 包、小说插件贡献）
        // 这类 QueueOperations 实现必须标 @PluginManagedBean、由各自 XxxPluginConfiguration 显式装配、排除出根包扫描——
        // 否则贡献它的插件被禁 / 卸载后，根扫描的 @Service / @Component 仍会注册适配器偷跑，破坏「缺操作即该作品类型不
        // 参与跨类型清空」语义（与作品类型执行器 ScheduledWorkRunner 同构）。接口本身不在约束面。
        classes()
                .that().areAssignableTo(
                        top.sywyar.pixivdownload.core.download.queue.QueueOperations.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("队列宿主操作适配器随贡献它的插件生命周期归属：必须 @PluginManagedBean 由对应 "
                        + "XxxPluginConfiguration 显式装配、排除出根包扫描，不得用 @Service / @Component 根扫描注册，"
                        + "否则插件禁用后仍被注册偷跑")
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

    @Test
    @DisplayName("quota 包不得依赖 download 包：配额打包排除 sidecar 经中性 WorkSidecarFiles 判定")
    void quotaDoesNotDependOnDownloadPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.quota..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download..")
                .because("quota 是配额管理包，配额打包排除 *.meta.json 经核心中性类 "
                        + "core.metadata.sidecar.WorkSidecarFiles 判定，不得反向依赖 download 包（消除 "
                        + "download ↔ quota 循环回潮）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("imageclassifier 包不得依赖 download.meta 包：sidecar 命名经中性 WorkSidecarFiles 判定")
    void imageClassifierDoesNotDependOnDownloadMetaPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.imageclassifier..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download.meta..")
                .because("ImageClassifier 是独立 Swing 应用，搬移图片时携带 sidecar 经核心中性类 "
                        + "core.metadata.sidecar.WorkSidecarFiles 判定文件名，不得反向依赖 download.meta 实现包")
                .check(CLASSES);
    }

    private static boolean contains(Path path, String needle) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(needle);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }
}
