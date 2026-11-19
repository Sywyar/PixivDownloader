package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("启动插件 artifact 依赖计划")
class PluginArtifactLoadPlanTest {

    @TempDir
    Path tempDir;

    @Test
    void headlessKeepsTransitiveRequiredDesktopPackagesButSkipsOptionalAndUnreferencedOnes() throws IOException {
        var backend = entry("backend", false, "adapter,optional?");
        var adapter = entry("adapter", true, "base");
        var base = entry("base", true, "");
        var optional = entry("optional", true, "");
        var unused = entry("unused", true, "");
        var plan = PluginArtifactLoadPlan.createInspected(List.of(backend, adapter, base, optional, unused));

        assertThat(plan.failures()).isEmpty();
        assertThat(plan.headlessEntries()).containsExactly(base, adapter, backend);
        assertThat(plan.orderedEntries()).containsExactlyInAnyOrder(base, adapter, backend, optional, unused);
    }

    private PluginArtifactLoadPlan.Entry entry(String id, boolean desktopOnly, String dependencies) throws IOException {
        Path file = tempDir.resolve(id + ".properties");
        Files.writeString(file, "plugin.id=" + id + "\nplugin.version=1.0.0\nplugin.class=example.Plugin\n"
                + "plugin.dependencies=" + dependencies + "\npixiv.execution-mode=host-process-full-trust\n"
                + (desktopOnly ? "pixiv.desktop-only=true\n" : ""), StandardCharsets.UTF_8);
        return new PluginArtifactLoadPlan.Entry(file, PluginPackageReader.inspectDescriptor(file));
    }

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
                .containsExactly("base", "base", "dependent.jar");
        assertThat(plan.failures().subList(0, 2)).allSatisfy(failure -> {
            assertThat(failure.phase()).isEqualTo("plugin-conflict");
            assertThat(failure.reason()).contains(first.artifactPath().toString(), second.artifactPath().toString());
        });
    }

    private static PluginDescriptor descriptor(String id, List<PluginDependencyRef> dependencies) {
        return new PluginDescriptor(id, id, "1.0.0", VersionRequirement.of(1, 0), dependencies,
                "com.example." + id + ".Plugin", null, id, null, null, null, PluginKind.FEATURE);
    }
}
