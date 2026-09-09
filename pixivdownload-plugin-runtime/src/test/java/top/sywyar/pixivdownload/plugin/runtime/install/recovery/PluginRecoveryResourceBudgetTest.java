package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@DisplayName("插件清点结构复用的资源预算")
class PluginRecoveryResourceBudgetTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("相同内容复用检视结果但严格限制和归档解释不能复用")
    void reuseRequiresIdenticalLimitsAndArchiveFormat() throws Exception {
        Path jar = PluginPackageFixtures.bareJar(directory.resolve("plugin.jar"),
                "plugin", "1.0.0", null, "example.Plugin");
        String digest = PluginPackageIntegrity.sha256Hex(jar);
        PluginPackageLimits limits = PluginPackageLimits.defaults();
        var first = new PluginRecoveryResourceBudget();
        var inspection = first.inspectArchive(jar, digest, limits);
        var next = new PluginRecoveryResourceBudget(first);
        try (var verifier = mockStatic(PluginPackageVerifier.class)) {
            assertThat(next.inspectArchive(jar, digest, limits)).isSameAs(inspection);
            verifier.verifyNoInteractions();
        }
        var strict = new PluginPackageLimits(limits.maxArchiveBytes(), 1,
                limits.maxTotalUncompressedBytes(), limits.maxEntryUncompressedBytes(),
                limits.maxDescriptorBytes(), limits.maxCompressionRatio());
        assertThatThrownBy(() -> next.inspectArchive(jar, digest, strict))
                .isInstanceOf(PluginPackageException.class);
        Path zip = java.nio.file.Files.copy(jar, directory.resolve("plugin.zip"));
        assertThat(new PluginRecoveryResourceBudget(first).inspectArchive(zip, digest, limits).format())
                .isNotEqualTo(inspection.format());
    }

    @Test
    @DisplayName("跨轮复用照常累计解压预算且新内容不能越过已耗尽预算")
    void reusedArchiveConsumesFreshRoundBudget() throws Exception {
        Path jar = PluginPackageFixtures.bareJar(directory.resolve("large.jar"),
                "large", "1.0.0", null, "example.Plugin");
        PluginPackageLimits limits = PluginPackageLimits.defaults();
        String digest = PluginPackageIntegrity.sha256Hex(jar);
        var previous = new PluginRecoveryResourceBudget();
        try (var verifier = mockStatic(PluginPackageVerifier.class)) {
            verifier.when(() -> PluginPackageVerifier.verifyAndMeasure(any(), any()))
                    .thenReturn(new PluginPackageVerifier.VerificationUsage(48_000, 672L << 20));
            previous.inspectArchive(jar, digest, limits);
            var current = new PluginRecoveryResourceBudget(previous);
            current.inspectArchive(jar, digest, limits);
            current.inspectArchive(jar, "second-content", limits);
            assertThatThrownBy(() -> current.inspectArchive(jar, "third-content", limits))
                    .isInstanceOf(PluginRecoveryValidationException.class);
            assertThat(current.exhausted()).isTrue();
        }
    }
}
