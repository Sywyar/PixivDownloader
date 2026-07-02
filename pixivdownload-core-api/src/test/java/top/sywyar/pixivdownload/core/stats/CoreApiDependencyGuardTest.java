package top.sywyar.pixivdownload.core.stats;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSeverity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * core-api 边界守卫：证明本模块保持轻量——核心 owned 的语义查询端口（{@link StatsQueryStore}）+ 纯 JDK 结果
 * DTO（{@link StatsAggregates}），Spring-free 纯 JDK（镜像 {@code plugin.api} 的框架洁净）。
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
                        "top.sywyar.pixivdownload.core.stats..",
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
    @DisplayName("core-api 不得依赖 Spring / SLF4J / JDBC / MyBatis 或 core.stats.db 实现层")
    void coreApiHasNoFrameworkOrImplDependency() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.slf4j..", "ch.qos.logback..",
                        "java.sql..", "javax.sql..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("core-api 只承载核心 owned 的语义查询端口（StatsQueryStore）与纯 JDK 结果 DTO"
                        + "（StatsAggregates）：Spring / 日志门面 / JDBC / MyBatis 与 mapper/repository 实现"
                        + "（core.stats.db.StatsQueryStoreImpl）全部留在 app 实现层，core-api 不得反向依赖它们")
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
}
