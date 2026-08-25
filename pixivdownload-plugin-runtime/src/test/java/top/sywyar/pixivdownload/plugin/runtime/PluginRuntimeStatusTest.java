package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginRuntimeStatusTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("运行时状态投影会替换旧插件复验并限制保留数量")
    void projectsRuntimePhasesAndRetainsBoundedLatestVerifications() {
        Map<String, PluginRuntimePackagePhase> phases = new LinkedHashMap<>();
        phases.put("alpha", PluginRuntimePackagePhase.STARTED);
        phases.put("beta", PluginRuntimePackagePhase.STOPPED);
        PluginRuntimeVerificationSnapshot oldAlpha = snapshot(tempDir.resolve("alpha-old.jar"), "alpha", 'a');
        PluginRuntimeVerificationSnapshot beta = snapshot(tempDir.resolve("beta.jar"), "beta", 'b');
        PluginRuntimeStatus initial = PluginRuntimeStatus.populated(
                tempDir,
                phases,
                List.of(new PluginLoadFailure("broken.jar", "broken")),
                List.of(oldAlpha, beta));
        PluginRuntimeVerificationSnapshot newAlpha = snapshot(tempDir.resolve("alpha-new.jar"), "alpha", 'c');
        PluginRuntimeVerificationSnapshot gamma = snapshot(tempDir.resolve("gamma.jar"), "gamma", 'd');

        PluginRuntimeStatus updated = initial.withLatestRuntimeVerifications(
                newAlpha.artifactPath(), List.of(newAlpha, gamma), 2);

        assertThat(updated.loadedPluginIds()).containsExactly("alpha", "beta");
        assertThat(updated.startedPluginIds()).containsExactly("alpha");
        assertThat(updated.failures()).singleElement()
                .extracting(PluginLoadFailure::source)
                .isEqualTo("broken.jar");
        assertThat(updated.verifications()).containsExactly(newAlpha, gamma);

        PluginRuntimeStatus refreshed = updated.refreshed(Map.of());
        assertThat(refreshed.state()).isEqualTo(PluginDirectoryState.EMPTY);
        assertThat(refreshed.loadedPluginIds()).isEmpty();
        assertThat(refreshed.failures()).isEmpty();
        assertThat(refreshed.verifications()).containsExactly(newAlpha, gamma);
    }

    private static PluginRuntimeVerificationSnapshot snapshot(Path path, String pluginId, char digestCharacter) {
        String digest = String.valueOf(digestCharacter).repeat(64);
        VerificationResult result = new VerificationResult(
                VerificationStatus.UNSIGNED_ALLOWED,
                pluginId,
                "1.0.0",
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                1L,
                digest,
                "UNSIGNED_ALLOWED");
        return new PluginRuntimeVerificationSnapshot(
                path, pluginId, "1.0.0", 1L, digest, null, result);
    }
}
