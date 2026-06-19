package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护任务↔插件归属守卫。禁用语义按 {@code PixivFeaturePlugin#maintenanceTasks()} 声明的归属跳过
 * 被禁用插件拥有的任务；故住在功能插件包内的维护任务若漏声明，禁用该插件时它会偷跑——本守卫拦住该缺口。
 */
@DisplayName("维护任务↔插件归属守卫")
class MaintenanceTaskOwnershipTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("功能包内的维护任务必须由某内置插件经 maintenanceTasks() 声明归属（核心 maintenance 包任务免）")
    void featurePackageMaintenanceTasksMustBeOwned() {
        Set<String> declared = BuiltInPlugins.createAll().stream()
                .flatMap(plugin -> plugin.maintenanceTasks().stream())
                .map(Class::getName)
                .collect(Collectors.toSet());

        List<JavaClass> impls = concreteMaintenanceTaskImpls();
        assertThat(impls).as("应存在至少一个维护任务实现").isNotEmpty();

        for (JavaClass impl : impls) {
            // 核心基础设施维护任务住 maintenance 包、始终执行、不归属任何插件，免声明；
            // 住在功能插件包内的维护任务必须由其插件声明，否则禁用该插件时它会偷跑。
            boolean coreInfra = impl.getPackageName().startsWith("top.sywyar.pixivdownload.maintenance");
            if (!coreInfra) {
                assertThat(declared)
                        .as("功能包内的维护任务 %s 必须由某插件 maintenanceTasks() 声明归属", impl.getName())
                        .contains(impl.getName());
            }
        }
    }

    @Test
    @DisplayName("插件声明的维护任务无重复归属")
    void declaredMaintenanceTasksHaveNoDuplicateOwnership() {
        List<Class<? extends MaintenanceTask>> declared = new ArrayList<>();
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            declared.addAll(plugin.maintenanceTasks());
        }
        assertThat(declared)
                .as("同一维护任务被多个插件声明会造成双重归属")
                .doesNotHaveDuplicates();
    }

    private static List<JavaClass> concreteMaintenanceTaskImpls() {
        List<JavaClass> impls = new ArrayList<>();
        for (JavaClass candidate : CLASSES) {
            if (candidate.isAssignableTo(MaintenanceTask.class)
                    && !candidate.isInterface()
                    && !candidate.getModifiers().contains(JavaModifier.ABSTRACT)) {
                impls.add(candidate);
            }
        }
        return impls;
    }
}
