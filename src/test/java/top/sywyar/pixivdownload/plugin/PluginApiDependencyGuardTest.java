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
    @DisplayName("下载工作台收编的 schedule 引擎 Bean 不得依赖核心计划任务数据实现层：只能经 core.schedule 语义 Store/API 读写")
    void scheduleEngineBeansMustNotAccessCoreScheduleImplDirectly() {
        // scheduled_tasks / scheduled_task_pending 是核心 owned schema；其语义数据访问门面 ScheduledTaskStore
        // 已是核心 owned 接口（core.schedule），底层 MyBatis ScheduledTaskMapper / schema 初始化 / 数据库方言适配
        // 收口在核心实现层 core.schedule.db。随 schedule 能力收编进下载工作台插件的引擎 Bean（ScheduleExecutor /
        // ScheduleService / ScheduleRunner / ScheduleController 等，均为 @PluginManagedBean）只能依赖 core.schedule
        // 语义 Store/API 与行模型，不得直接依赖 core.schedule.db 实现层、池化 DataSource、JdbcTemplate、裸 Connection
        // 或 MyBatis 自由 SQL 访问核心表。
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
                        + "收口在核心实现层 core.schedule.db。收编进下载工作台插件的 schedule 引擎 Bean 只能依赖 "
                        + "core.schedule 语义 Store/API 与行模型，不得直接依赖 core.schedule.db 实现层、ScheduledTaskMapper / "
                        + "DataSource / JdbcTemplate / Connection / MyBatis 自由 SQL 访问核心表")
                .check(CLASSES);
    }

    @Test
    @DisplayName("schedule 编排层不得依赖 novel 插件包：小说下载 / 系列合订 / 翻译状态只能经核心契约 ScheduledWorkRunner")
    void scheduleDoesNotDependOnNovelPlugin() {
        // 计划任务的小说一侧（构造 NovelDownloadRequest + downloadBlocking + 系列合订 + 队列视图翻译状态叠加）已收口
        // 到核心契约 core.schedule.work.ScheduledWorkRunner（按作品类型解析、由小说插件贡献小说执行器实现）。调度编排层
        //（schedule 包：执行器 / 服务 / 来源 provider / 上下文 / 插画执行器等）只依赖该核心接口与中性载体，不得 import
        // 任何 novel 包类型——发现 / 服务端筛选 / 系列富信息补全 / sidecar 捕获 / 异常分类 / 限流 / 熔断 / 代理 / 运行队列
        // 等共享调度机器仍留调度壳，小说插件经正常 plugin→core 方向实现契约，故无 schedule↔novel 互相 import。
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
    @DisplayName("下载工作台 schedule 装配层不得 import novel 包：执行器装配只经核心契约 + 注册中心")
    void downloadWorkbenchScheduleAssemblyDoesNotImportNovel() {
        // 下载工作台插件配置装配 schedule 引擎 Bean（ScheduleExecutor / ScheduleService / 插画执行器等）+ 注入核心
        // 作品类型执行器注册中心 ScheduledWorkRunnerRegistry。它不得 import 任何 novel 包类型——小说执行器由
        // NovelPluginConfiguration 贡献、经注册中心按 kind 解析，装配层只依赖核心契约。
        noClasses()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.DownloadWorkbenchPluginConfiguration")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("下载工作台 schedule 装配层经核心契约 ScheduledWorkRunner + 注册中心 "
                        + "ScheduledWorkRunnerRegistry 装配，小说执行器由小说插件贡献，装配层不得 import 任何 novel 包类型")
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
