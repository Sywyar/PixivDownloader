package top.sywyar.pixivdownload.startup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("发行 AOT 缓存身份")
class DistributionLauncherTest {
    @TempDir Path directory;

    @Test
    @DisplayName("缓存身份随依赖字节和 JVM 参数改变，不依赖文件时间")
    void invalidatesChangedDependencyAndOptions() throws Exception {
        Path jar = Files.writeString(directory.resolve("application.jar"), "host", StandardCharsets.UTF_8);
        Path library = Files.createDirectory(directory.resolve("lib")).resolve("dependency.jar");
        Files.writeString(library, "original", StandardCharsets.UTF_8);
        var timestamp = Files.getLastModifiedTime(library);
        String before = DistributionLauncher.identity(jar, List.of());
        Files.writeString(library, "modified", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(library, timestamp);
        assertThat(DistributionLauncher.identity(jar, List.of())).isNotEqualTo(before);
        Files.writeString(library, "original", StandardCharsets.UTF_8);
        assertThat(DistributionLauncher.identity(jar, List.of())).isEqualTo(before);
        assertThat(DistributionLauncher.identity(jar, List.of("-Xmx256m"))).isNotEqualTo(before);
    }

    @Test
    @DisplayName("未完成、内容损坏和不同运行环境的缓存不可复用")
    void rejectsIncompleteOrChangedCache() throws Exception {
        Path cache = directory.resolve("application.aot"), identity = directory.resolve("identity");
        String key = "a".repeat(64);
        assertThat(DistributionLauncher.validCache(cache, identity, key)).isFalse();
        byte[] bytes = "recorded".getBytes(StandardCharsets.UTF_8);
        Files.write(cache, bytes);
        Files.writeString(identity, key + "\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), StandardCharsets.UTF_8);
        assertThat(DistributionLauncher.validCache(cache, identity, key)).isTrue();
        assertThat(DistributionLauncher.validCache(cache, identity, "b".repeat(64))).isFalse();
        Files.writeString(cache, "tampered", StandardCharsets.UTF_8);
        assertThat(DistributionLauncher.validCache(cache, identity, key)).isFalse();
    }

    @Test @DisplayName("随包缓存允许安装路径变化，但依赖、插件及 VM 选项变化必须失效")
    void bundledIdentity() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        for (Path root : List.of(first, second)) {
            Files.writeString(root.resolve("app.jar"), "host", StandardCharsets.UTF_8);
            Files.writeString(Files.createDirectory(root.resolve("lib")).resolve("shared.jar"), "library", StandardCharsets.UTF_8);
            Files.createDirectory(root.resolve("plugins"));
        }
        var inputs = StartupCacheInputs.capture(first.resolve("plugins"), first.resolve("config.yaml"));
        var relocated = StartupCacheInputs.capture(second.resolve("plugins"), second.resolve("config.yaml"));
        String key = StartupCacheTraining.bundledIdentity(first.resolve("app.jar"), List.of("-Dinstance=first"), inputs);
        assertThat(StartupCacheTraining.bundledIdentity(second.resolve("app.jar"), List.of("-Dinstance=second"), relocated)).isEqualTo(key);
        assertThat(StartupCacheTraining.bundledIdentity(first.resolve("app.jar"), List.of("-Xmx512m"), inputs)).isNotEqualTo(key);
        Files.writeString(second.resolve("plugins/new.jar"), "plugin", StandardCharsets.UTF_8);
        assertThat(StartupCacheTraining.bundledIdentity(second.resolve("app.jar"), List.of(),
                StartupCacheInputs.capture(second.resolve("plugins"), second.resolve("config.yaml")))).isNotEqualTo(key);
        Files.writeString(second.resolve("lib/shared.jar"), "changed", StandardCharsets.UTF_8);
        assertThat(StartupCacheTraining.bundledIdentity(second.resolve("app.jar"), List.of(), relocated)).isNotEqualTo(key);
    }
}
