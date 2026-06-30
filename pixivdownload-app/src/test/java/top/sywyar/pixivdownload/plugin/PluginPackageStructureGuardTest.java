package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件包结构防增长守卫：把「宽根包直接类型只降不升」与「bootstrap 子包 Spring-free」固化为实际执行的守卫。
 * <ul>
 *   <li>app 的 {@code plugin} 根包直接类型 ≤ 56；plugin-runtime 的 {@code plugin.runtime} 根包 ≤ 13、
 *       {@code plugin.runtime.install} 根包 ≤ 18（当前基线，只降不升）。</li>
 *   <li>GUI bootstrap 新类全部落在 {@code plugin.runtime.bootstrap} 子包，不堆进宽根包。</li>
 *   <li>{@code plugin.runtime.bootstrap} 子包<b>零</b> Spring / Swing / FlatLaf / JNA / app 包依赖——
 *       它是 Spring-free 中性载体（较宽的 {@code plugin.runtime} 树本身允许 Spring，故此约束是该子包的专项守卫）。</li>
 * </ul>
 * 直接类型 = 非嵌套的顶级类型，与各根包 {@code .java} 文件数一致（这些包无 package-info、每文件一个顶级类型）。
 */
@DisplayName("插件包结构防增长守卫：宽根包只降不升（56/13/18）+ bootstrap 子包 Spring-free")
class PluginPackageStructureGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    private static final String PLUGIN_ROOT = "top.sywyar.pixivdownload.plugin";
    private static final String RUNTIME_ROOT = "top.sywyar.pixivdownload.plugin.runtime";
    private static final String INSTALL_ROOT = "top.sywyar.pixivdownload.plugin.runtime.install";
    private static final String BOOTSTRAP_ROOT = "top.sywyar.pixivdownload.plugin.runtime.bootstrap";

    /**
     * {@code top.sywyar.pixivdownload.plugin} 是<b>拆分包</b>：app 的 56 个 plugin 根包类型 +
     * plugin-runtime 的启用三件套（{@code ConditionalOnPluginEnabled} / {@code OnPluginEnabledCondition} /
     * {@code PluginToggleProperties}，包名相同但属 plugin-runtime 模块）。ArchUnit 在合并 classpath 上无法区分模块来源，
     * 故按简单名排除这 3 个 plugin-runtime 拆分类型，得到 app 自有的 56。
     */
    private static final java.util.Set<String> PLUGIN_RUNTIME_SPLIT_TYPES = java.util.Set.of(
            "ConditionalOnPluginEnabled", "OnPluginEnabledCondition", "PluginToggleProperties");

    @Test
    @DisplayName("app 的 plugin 根包直接类型 ≤ 56（只降不升基线，排除 plugin-runtime 拆分包三件套）")
    void appPluginRootPackageDoesNotGrow() {
        long appOwned = CLASSES.stream()
                .filter(c -> c.getPackageName().equals(PLUGIN_ROOT))
                .filter(c -> !c.isNestedClass())
                .filter(c -> !PLUGIN_RUNTIME_SPLIT_TYPES.contains(c.getSimpleName()))
                .count();
        assertThat(appOwned)
                .as("app plugin 根包直接类型数（基线 56，只降不升；已排除 plugin-runtime 拆分包三件套）")
                .isLessThanOrEqualTo(56);
    }

    @Test
    @DisplayName("plugin-runtime 的 plugin.runtime 根包直接类型 ≤ 13（只降不升基线）")
    void runtimeRootPackageDoesNotGrow() {
        assertThat(directTypes(RUNTIME_ROOT))
                .as("plugin.runtime 根包直接类型数（基线 13，只降不升）")
                .isLessThanOrEqualTo(13);
    }

    @Test
    @DisplayName("plugin-runtime 的 plugin.runtime.install 根包直接类型 ≤ 18（只降不升基线）")
    void installRootPackageDoesNotGrow() {
        assertThat(directTypes(INSTALL_ROOT))
                .as("plugin.runtime.install 根包直接类型数（基线 18，只降不升）")
                .isLessThanOrEqualTo(18);
    }

    @Test
    @DisplayName("bootstrap 新类全部位于 plugin.runtime.bootstrap 子包（不落入宽根包），守卫实际执行非空")
    void bootstrapClassesLiveInDedicatedSubpackage() {
        assertThat(directTypes(BOOTSTRAP_ROOT))
                .as("bootstrap 子包直接类型数（PluginBootstrapSession / PluginEnabledSnapshot / PluginBootstrapSessionHandoff）")
                .isGreaterThanOrEqualTo(3);
        assertThat(CLASSES.stream()
                .filter(c -> c.getSimpleName().equals("PluginBootstrapSession"))
                .map(JavaClass::getPackageName)
                .distinct().toList())
                .as("PluginBootstrapSession 必须在 bootstrap 子包、不堆进 plugin.runtime 根包")
                .containsExactly(BOOTSTRAP_ROOT);
    }

    @Test
    @DisplayName("plugin.runtime.bootstrap 子包零 Spring / Swing / FlatLaf / JNA / app 包依赖（Spring-free 中性载体）")
    void bootstrapSubpackageIsSpringFreeAndHasNoAppDependencies() {
        noClasses().that().resideInAPackage(BOOTSTRAP_ROOT + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "javax.swing..",
                        "java.awt..",
                        "com.formdev..",
                        "com.sun.jna..",
                        "top.sywyar.pixivdownload.gui..",
                        "top.sywyar.pixivdownload.config..")
                .because("bootstrap 会话是 plugin-runtime 的 Spring-free 中性载体：不 import Spring / Swing / FlatLaf / JNA，"
                        + "也不反向依赖 app 的 gui / config 包（启用快照由 app 读 config 后以纯 JDK 形式传入）；较宽的 "
                        + "plugin.runtime 树本身允许 Spring，故 bootstrap 子包的零 Spring 是新增 gate")
                .check(CLASSES);
    }

    /** 指定包的直接（非嵌套顶级）类型数，与该包 .java 文件数一致。 */
    private static long directTypes(String packageName) {
        return CLASSES.stream()
                .filter(c -> c.getPackageName().equals(packageName))
                .filter(c -> !c.isNestedClass())
                .count();
    }
}
