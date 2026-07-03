package top.sywyar.pixivdownload.duplicate;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("duplicate 模块边界守卫")
class DuplicatePluginModuleDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.duplicate");

    @Test
    @DisplayName("duplicate 托管 Bean 不得直连数据库底层")
    void duplicateManagedBeansDoNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("疑似重复插件读取核心 Hash 事实可经 core.hash 与 PixivDatabase 语义服务，"
                        + "不得自建 JDBC/MyBatis 访问核心表")
                .check(CLASSES);
    }

    @Test
    @DisplayName("duplicate 模块不依赖 gallery / novel / download / stats 插件实现")
    void duplicateModuleDoesNotDependOnOtherFeatureImplementations() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.gallery..",
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.stats..")
                .because("duplicate 只消费核心 Hash / 作品事实，不应反向依赖其它功能插件实现")
                .check(CLASSES);
    }

    @Test
    @DisplayName("duplicate 模块应包含插件本体、PF4J 主类、配置类和业务 Bean（防空跑）")
    void duplicateModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(DuplicatePf4jPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicatePlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicatePluginConfiguration.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateController.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateService.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateScanService.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateHashBackfillTask.class.getName())).isTrue();
    }
}
