package top.sywyar.pixivdownload.plugin.runtime.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件运行期包索引")
class PluginRuntimePackageIndexTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("移除和重新扫描保留代际而进程关闭重置代际")
    void preservesGenerationUntilProcessShutdown() {
        PluginRuntimePackageIndex index = new PluginRuntimePackageIndex();

        index.add("sample", tempDir.resolve("sample.jar"), tempDir.resolve("load"),
                "1.0.0", PluginRuntimePackagePhase.LOADED, descriptor("sample", "1.0.0"), null);
        assertThat(index.generation("sample")).contains(1L);

        index.remove("sample");
        index.add("sample", tempDir.resolve("sample.jar"), tempDir.resolve("load-2"),
                "1.0.0", PluginRuntimePackagePhase.LOADED, descriptor("sample", "1.0.0"), null);
        assertThat(index.generation("sample")).contains(2L);

        index.clearEntries();
        index.add("sample", tempDir.resolve("sample.jar"), tempDir.resolve("load-3"),
                "1.0.0", PluginRuntimePackagePhase.LOADED, descriptor("sample", "1.0.0"), null);
        assertThat(index.generation("sample")).contains(3L);

        index.clearAll();
        index.add("sample", tempDir.resolve("sample.jar"), tempDir.resolve("load-4"),
                "1.0.0", PluginRuntimePackagePhase.LOADED, descriptor("sample", "1.0.0"), null);
        assertThat(index.generation("sample")).contains(1L);
    }

    @Test
    @DisplayName("阶段、描述符和准入快照由同一条目更新并投影")
    void updatesRuntimeEntryState() {
        PluginRuntimePackageIndex index = new PluginRuntimePackageIndex();
        PluginRuntimePackageIndex.Entry entry = index.add(
                "sample",
                tempDir.resolve("sample.jar"),
                tempDir.resolve("load"),
                "1.0.0",
                PluginRuntimePackagePhase.LOADED,
                descriptor("sample", "1.0.0"),
                null);
        PluginInventory inventory = PluginInventory.empty();

        entry.updatePhase(PluginRuntimePackagePhase.STARTED);
        entry.updateDescriptor(descriptor("sample", "1.1.0"));
        entry.updateContributionSnapshot(inventory);

        index.add("second", tempDir.resolve("second.jar"), tempDir.resolve("second-load"),
                "1.0.0", PluginRuntimePackagePhase.LOADED, descriptor("second", "1.0.0"), null);

        assertThat(index.packagePhases()).containsEntry("sample", PluginRuntimePackagePhase.STARTED);
        assertThat(index.packagePhases().keySet()).containsExactly("sample", "second");
        assertThat(index.descriptor("sample")).get().extracting(PluginDescriptor::version).isEqualTo("1.1.0");
        assertThat(index.entries()).extracting(PluginRuntimePackageIndex.Entry::packageId)
                .containsExactly("sample", "second");
        assertThat(entry.contributionSnapshot()).isSameAs(inventory);
        assertThat(index.isDevelopmentArtifact("sample")).isTrue();
    }

    private static PluginDescriptor descriptor(String id, String version) {
        return new PluginDescriptor(
                id,
                id,
                version,
                VersionRequirement.of(1, 0),
                List.of(),
                "com.example.SamplePlugin",
                null,
                id,
                null,
                null,
                null,
                PluginKind.FEATURE);
    }
}
