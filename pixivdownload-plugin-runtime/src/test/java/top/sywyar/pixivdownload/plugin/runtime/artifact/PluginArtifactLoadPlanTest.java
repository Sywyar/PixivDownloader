package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("启动插件 artifact 依赖计划")
class PluginArtifactLoadPlanTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("已检查描述符可在原路径不存在时规划且不会重新打开 artifact")
    void plansPreinspectedEntriesWithoutReopeningArtifactPaths() {
        PluginArtifactLoadPlan.Entry dependent = new PluginArtifactLoadPlan.Entry(
                tempDir.resolve("missing-dependent.jar"), descriptor("dependent",
                List.of(new PluginDependencyRef("base", "1.0", false))));
        PluginArtifactLoadPlan.Entry base = new PluginArtifactLoadPlan.Entry(
                tempDir.resolve("missing-base.jar"), descriptor("base", List.of()));

        PluginArtifactLoadPlan plan = PluginArtifactLoadPlan.createInspected(List.of(dependent, base));

        assertThat(plan.failures()).isEmpty();
        assertThat(plan.orderedEntries()).extracting(PluginArtifactLoadPlan.Entry::pluginId)
                .containsExactly("base", "dependent");
    }

    @Test
    @DisplayName("同一 plugin id 的全部候选均拒绝且依赖方不会任意绑定其中一份")
    void duplicatePluginIdsBlockEveryCandidateAndDependent() {
        PluginArtifactLoadPlan.Entry first = new PluginArtifactLoadPlan.Entry(
                tempDir.resolve("a-base.jar"), descriptor("base", List.of()));
        PluginArtifactLoadPlan.Entry second = new PluginArtifactLoadPlan.Entry(
                tempDir.resolve("b-base.jar"), descriptor("base", List.of()));
        PluginArtifactLoadPlan.Entry dependent = new PluginArtifactLoadPlan.Entry(
                tempDir.resolve("dependent.jar"), descriptor("dependent",
                List.of(new PluginDependencyRef("base", "1.0", false))));

        PluginArtifactLoadPlan plan = PluginArtifactLoadPlan.createInspected(List.of(first, second, dependent));

        assertThat(plan.orderedEntries()).isEmpty();
        assertThat(plan.skippedPluginIds()).containsExactlyInAnyOrder("base", "dependent");
        assertThat(plan.failures()).extracting(failure -> failure.source())
                .contains("a-base.jar", "b-base.jar", "dependent.jar");
    }

    private static PluginDescriptor descriptor(String id, List<PluginDependencyRef> dependencies) {
        return new PluginDescriptor(id, id, "1.0.0", PluginApiRequirement.of(1, 0), dependencies,
                "com.example." + id + ".Plugin", null, id, null, null, null, PluginKind.FEATURE);
    }
}
