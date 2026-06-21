package top.sywyar.pixivdownload.stats;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * stats 插件模块边界守卫：证明本模块只承载统计仪表盘插件本体（{@link StatsPlugin} /
 * {@link StatsPluginConfiguration} / {@link StatsService} / {@link StatsController} / {@link StatsDto}），
 * 只依赖跨插件契约 {@code plugin.api}、核心语义端口 {@code core.stats}（core-api）、插件启用运行时
 * {@code top.sywyar.pixivdownload.plugin}（plugin-runtime 的 {@code @ConditionalOnPluginEnabled}）与
 * Spring / JDK / Lombok，<b>绝不反向依赖主程序 pixivdownload-app</b>（app 经唯一边
 * {@code BuiltInPlugins→StatsPlugin} 单向依赖本模块、{@code app ↔ stats} 无环）。
 *
 * <p>统计事实数据只经 core-api 语义端口 {@link top.sywyar.pixivdownload.core.stats.StatsQueryStore} 读，
 * 不得自建 {@code JdbcTemplate} / 注入 {@code DataSource} / 触碰核心 DB 实现层 {@code core.stats.db}。
 *
 * <p>本守卫在 {@code pixivdownload-plugin-stats} 模块内自包含运行：{@link ClassFileImporter} 只扫描本模块
 * classpath 上的类（app / 其它插件类不在本模块 classpath 上），与主程序的
 * {@code PluginApiDependencyGuardTest}、core-api 的 {@code CoreApiDependencyGuardTest}、plugin-runtime 的
 * {@code PluginRuntimeDependencyGuardTest} 正交、各自从自己模块的 classpath 断言。
 */
class StatsPluginModuleDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("stats 模块必须自包含：只依赖 plugin.api / core.stats 端口 / plugin 运行时 / Spring / JDK / Lombok，不依赖 app")
    void statsModuleIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.stats..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.stats..",
                        "top.sywyar.pixivdownload.plugin.api..",
                        "top.sywyar.pixivdownload.plugin",
                        "top.sywyar.pixivdownload.core.stats..",
                        "java..",
                        "org.springframework..",
                        "lombok..")
                .because("stats 插件本体只能依赖跨插件契约 plugin.api、核心语义端口 core.stats（core-api）、"
                        + "插件启用运行时 top.sywyar.pixivdownload.plugin（plugin-runtime 的 @ConditionalOnPluginEnabled）"
                        + "与 Spring / JDK / Lombok；绝不依赖主程序 pixivdownload-app 的任何业务实现包——"
                        + "否则 app↔stats 成环、模块无法构建。app 经唯一边 BuiltInPlugins→StatsPlugin 单向依赖本模块")
                .check(CLASSES);
    }

    @Test
    @DisplayName("stats 模块不得直连数据库底层：统计事实只能经 core-api 语义端口读")
    void statsModuleDoesNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.stats..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..", "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..")
                .because("统计聚合 SQL 收口在核心实现层 core.stats.db.StatsQueryStoreImpl（留 app）；"
                        + "stats 插件托管 Bean 只能经 core-api 语义端口 StatsQueryStore 取聚合结果，"
                        + "不得自建 JdbcTemplate / 注入池化 DataSource / 直依赖 MyBatis 或裸 JDBC 访问核心表")
                .check(CLASSES);
    }

    @Test
    @DisplayName("stats 模块只经 core-api 语义端口接触统计事实：不得依赖 core.stats.db 实现层")
    void statsModuleTouchesStatsFactsOnlyViaCoreApiPort() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.stats..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.core.stats.db..")
                .because("core.stats.db.StatsQueryStoreImpl（@Repository + NamedParameterJdbcTemplate + 聚合 SQL）"
                        + "是核心实现层、留在 app；stats 插件只能依赖 core-api 的语义端口 StatsQueryStore 与纯 JDK "
                        + "结果 DTO StatsAggregates，不得反向依赖其实现层（实现层也不在本模块 classpath 上）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("stats 模块应包含统计插件五类（防守卫 vacuous 通过）")
    void statsModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(StatsPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(StatsPluginConfiguration.class.getName())).isTrue();
        assertThat(CLASSES.contain(StatsService.class.getName())).isTrue();
        assertThat(CLASSES.contain(StatsController.class.getName())).isTrue();
        assertThat(CLASSES.contain(StatsDto.class.getName())).isTrue();
    }
}
