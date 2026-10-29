package top.sywyar.pixivdownload.schedule;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

@DisplayName("宿主调度和通用下载边界")
class ScheduleHostDependencyGuardTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("top.sywyar.pixivdownload.schedule", "top.sywyar.pixivdownload.core.download.web");
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("pixivdownload-app"))) {
            directory = directory.getParent();
        }
        return java.util.Objects.requireNonNull(directory, "repository root");
    }
    private static String read(Path path) {
        try { return Files.readString(path, StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    @Test
    @DisplayName("schedule 宿主不得依赖 novel 插件包")
    void scheduleDoesNotDependOnNovelPlugin() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("计划任务宿主只能经 plugin-api ScheduledWorkExecutor 契约派发小说作品，不得 import novel 包")
                .check(CLASSES);
    }

    @Test
    @DisplayName("通用 schedule 宿主不得依赖下载工作台的具体计划策略")
    void scheduleHostDoesNotDependOnConcreteWorkbenchSchedulePolicy() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download.schedule..")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin")
                .because("调度宿主只能经稳定契约接收来源、凭证、风险与持久化贡献")
                .check(CLASSES);
    }

    @Test
    @DisplayName("通用 schedule 源码不得重新解释 Pixiv 凭证策略 wire")
    void scheduleHostSourceDoesNotInterpretPixivCredentialPolicy() throws IOException {
        Path sourceRoot = repositoryRoot().resolve(
                "pixivdownload-app/src/main/java/"
                        + "top/sywyar/pixivdownload/schedule");
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sources = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        assertThat(sources)
                .as("generic schedule source set must be non-vacuous")
                .hasSizeGreaterThan(20);
        String[] forbidden = {
                "top.sywyar.pixivdownload.download.schedule.",
                "DownloadWorkbenchPlugin",
                "PixivScheduled",
                "PixivSchedule",
                "PIXIV_OVERUSE",
                "CookieAuthorizeRequest",
                "AccountResumeRequest",
                "authorize-cookie",
                "revoke-cookie",
                "/accounts/",
                "cookieMode",
                "cookieBound",
                "accountId",
                "ackWarningTime"
        };
        for (Path source : sources) {
            assertThat(read(source))
                    .as(repositoryRoot().relativize(source).toString())
                    .doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("计划队列投影不得解释具体作品类型与插件私有实时状态")
    void scheduleQueueProjectionDoesNotInterpretPluginPrivateLiveStatus() {
        Path root = repositoryRoot().resolve(
                "pixivdownload-app/src/main/java/top/sywyar/pixivdownload");
        List<Path> genericHostSources = List.of(
                root.resolve("schedule/ScheduleRunQueue.java"),
                root.resolve("schedule/ScheduleService.java"),
                root.resolve("schedule/ScheduleExecutor.java"),
                root.resolve("schedule/execution/ScheduleWorkCoordinator.java"),
                root.resolve("schedule/dto/ScheduleQueueView.java"));
        List<String> forbidden = List.of(
                "KIND_NOVEL",
                "WORK_TYPE_ILLUST",
                "WORK_TYPE_NOVEL",
                "autoTranslateSubmitted",
                "TranslateStatus",
                "translatePhase",
                "xRestrict",
                "pixiv.net/artworks",
                "novel/show.php");

        for (Path source : genericHostSources) {
            assertThat(read(source))
                    .as(source.getFileName().toString())
                    .doesNotContain(forbidden.toArray(String[]::new));
        }
    }

    @Test
    @DisplayName("计划任务宿主 Bean 不得直连核心计划任务数据实现层")
    void scheduleEngineBeansMustNotAccessCoreScheduleImplDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "javax.sql..", "java.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..")
                .because("scheduled_tasks / scheduled_task_pending 是核心 owned schema，调度壳只能经 core.schedule 语义 Store/API")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载队列控制器不得直接依赖具体作品类型下载服务")
    void downloadQueueControllerDoesNotDependOnConcreteDownloadServices() {
        noClasses()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.core.download.web.DownloadQueueController")
                .should().dependOnClassesThat(new DescribedPredicate<JavaClass>("concrete download service") {
                    public boolean test(JavaClass type) { return type.getPackageName().startsWith("top.sywyar.pixivdownload.download") || type.getPackageName().startsWith("top.sywyar.pixivdownload.novel.download"); }
                })
                .because("下载队列控制器的跨类型取消 / 清空只能经稳定 DownloadControlPlane")
                .check(CLASSES);
    }

    @Test
    @DisplayName("计划任务宿主只通过 plugin-api 稳定端口访问能力")
    void scheduleHostUsesStableCapabilityAccess() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat(new DescribedPredicate<JavaClass>("registry implementation") {
                    public boolean test(JavaClass type) { return type.getPackageName().startsWith("top.sywyar.pixivdownload.core.schedule.capability"); }
                })
                .because("调度编排继续通过稳定 capability 端口取得插件能力")
                .check(CLASSES);

        classes()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.schedule.ScheduleHostConfiguration")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess")
                .because("宿主配置必须注入稳定计划能力访问端口")
                .check(CLASSES);

        classes()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess")
                .because("执行引擎必须通过稳定端口取得 planning 与 execution lease")
                .check(CLASSES);
    }
}
