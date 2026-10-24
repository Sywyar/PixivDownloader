package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime capability 边界守卫：生命周期核心只认识中性 adapter 契约，
 * 不把具体能力类型列表写回中心注册器。
 */
@DisplayName("运行期能力边界守卫")
class PluginCapabilityBoundaryGuardTest {

    private static final String LIFECYCLE_PACKAGE = "top.sywyar.pixivdownload.plugin.lifecycle";
    private static final ArchRule NEUTRAL_CORE = noClasses()
            .should().dependOnClassesThat().haveNameMatching(
                    ".*\\.(NotificationSink|NotificationSinkRegistry|PushChannel|PushChannelRegistry"
                            + "|AiChatClient|AiChatClientRegistry|NarrationVoiceEngine|NarrationEngineRegistry)")
            .because("具体能力由 capability 子包的适配器封装，生命周期核心只依赖中性契约");

    @Test
    @DisplayName("生命周期核心不得硬编码具体能力类型或注册中心")
    void lifecycleCoreDoesNotHardcodeConcreteCapabilityTypes() {
        JavaClasses core = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(LIFECYCLE_PACKAGE)
                .that(resideOutsideOfPackage(LIFECYCLE_PACKAGE + ".capability.."));
        NEUTRAL_CORE.check(core);
    }

    @Test
    @DisplayName("字节码守卫允许中性适配器及普通文本并拒绝具体能力依赖")
    void guardDistinguishesDependenciesFromOrdinaryText() {
        NEUTRAL_CORE.check(new ClassFileImporter().importClasses(NeutralCore.class));
        assertThat(NEUTRAL_CORE.evaluate(new ClassFileImporter()
                .importClasses(ConcreteCapabilityCore.class)).hasViolation()).isTrue();
    }

    private static final class NeutralCore {
        List<PluginCapabilityContributionAdapter<?>> adapters;
        String description = "NotificationSink";
    }

    private static final class ConcreteCapabilityCore {
        top.sywyar.pixivdownload.notification.NotificationSink sink;
    }
}
