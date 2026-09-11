package top.sywyar.pixivdownload.sdk.development;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SDK 固定运行包与隔离缓存")
class SdkRuntimeArchiveTest {
    @TempDir
    Path temp;

    @Test
    @DisplayName("同一固定缓存可供两个中文项目使用，运行副本变化不污染缓存或另一个项目")
    void cachedBytesProduceIndependentRunsAndRejectTampering() throws Exception {
        var fixture = fixture();
        Path cache = Files.createDirectories(temp.resolve("cache"));
        Path cached = cache.resolve(fixture.lock.runtime().archive().sha256() + ".zip");
        Files.copy(fixture.zip, cached);
        assertThat(SdkRuntimeArchive.cached(fixture.lock, cache)).isEqualTo(cached);
        Path first = temp.resolve("中文 项目/.dev/runs/first");
        Path second = temp.resolve("another project/.dev/runs/second");
        SdkRuntimeArchive.prepare(fixture.lock, cached, first);
        SdkRuntimeArchive.prepare(fixture.lock, cached, second);
        Path host = first.resolve(fixture.lock.runtime().host().file());
        Files.writeString(host, "modified-runtime", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SdkRuntimeArchive.verifyBaseline(fixture.lock, first))
                .isInstanceOf(IOException.class).hasMessageContaining("ARTIFACT_MISMATCH");
        SdkRuntimeArchive.verifyBaseline(fixture.lock, second);
        SdkRuntimeArchive.verify(cached, fixture.lock.runtime().archive());
        assertThatThrownBy(() -> SdkRuntimeArchive.prepare(fixture.lock, cached, first))
                .isInstanceOf(IOException.class).hasMessageContaining("RUN_NOT_EMPTY");
        byte[] modified = Files.readAllBytes(cached);
        modified[modified.length / 2] ^= 1;
        Files.write(cached, modified);
        assertThatThrownBy(() -> SdkRuntimeArchive.cached(fixture.lock, cache))
                .isInstanceOf(IOException.class).hasMessageContaining("ARTIFACT_MISMATCH");
        assertThatThrownBy(() -> SdkRuntimeArchive.prepare(fixture.lock, cached, temp.resolve("bad-run")))
                .isInstanceOf(IOException.class).hasMessageContaining("ARTIFACT_MISMATCH");
    }

    @Test
    @DisplayName("元数据固定源码与 Release 地址，不接受 latest、历史 schema 或重复键")
    void projectMetadataBindsExactReleaseIdentity() throws Exception {
        var fixture = fixture();
        var project = new LinkedHashMap<String, Object>();
        project.put("schemaVersion", 3);
        project.put("javaVersion", Integer.parseInt(System.getProperty("sdk.test.java-version")));
        project.put("sdkVersion", fixture.lock.sdkVersion());
        project.put("releaseId", fixture.lock.releaseId());
        project.put("sourceCommitSha", fixture.lock.sourceCommitSha());
        project.put("developmentRuntime", fixture.lock.runtime());
        Path json = temp.resolve("sdk-project.json");
        SdkRuntimeLock.JSON.writeValue(json.toFile(), project);
        assertThat(SdkRuntimeLock.read(temp)).isEqualTo(fixture.lock);
        String original = Files.readString(json, StandardCharsets.UTF_8);
        Files.writeString(json, original.replace("/releases/download/" + fixture.lock.releaseId() + "/", "/releases/latest/download/"));
        assertThatThrownBy(() -> SdkRuntimeLock.read(temp)).isInstanceOf(IOException.class)
                .hasMessageContaining("URL_IDENTITY_MISMATCH");
        Files.writeString(json, original.replace("\"schemaVersion\":3", "\"schemaVersion\":2"));
        assertThatThrownBy(() -> SdkRuntimeLock.read(temp)).isInstanceOf(IOException.class)
                .hasMessageContaining("UNSUPPORTED_PROJECT");
        String javaVersion = "\"javaVersion\":" + project.get("javaVersion");
        Files.writeString(json, original.replace(javaVersion, javaVersion + "," + javaVersion));
        assertThatThrownBy(() -> SdkRuntimeLock.read(temp)).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "/absolute", "C:/absolute", "a/../../escape", "a\\escape",
            "a/CON.txt", "a/trailing.", "a/with:stream", "a//file"})
    @DisplayName("归档路径不越界，也不接受不可移植的 Windows 文件名")
    void rejectsUnsafeArchivePaths(String path) throws Exception {
        Path zip = zip("unsafe.zip", Map.of(path, bytes("bad")));
        assertThatThrownBy(() -> SdkRuntimeArchive.extract(zip, temp.resolve("extract")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SDK_UNSAFE_PATH");
        assertThat(temp.resolve("escape")).doesNotExist();
    }

    @Test
    @DisplayName("拒绝大小写路径碰撞、损坏 ZIP 和额外插件")
    void rejectsAmbiguousOrIncompleteRuntime() throws Exception {
        Path collision = zip("collision.zip", Map.of("Plugins/a.jar", bytes("a"), "plugins/b.jar", bytes("b")));
        assertThatThrownBy(() -> SdkRuntimeArchive.extract(collision, temp.resolve("collision")))
                .isInstanceOf(IOException.class).hasMessageContaining("PATH_COLLISION");
        var fixture = fixture();
        byte[] truncated = Files.readAllBytes(fixture.zip);
        Path bad = temp.resolve("truncated.zip");
        Files.write(bad, java.util.Arrays.copyOf(truncated, truncated.length - 10));
        assertThatThrownBy(() -> SdkRuntimeArchive.extract(bad, temp.resolve("truncated")))
                .isInstanceOf(IOException.class);
        Path run = temp.resolve("valid-run");
        SdkRuntimeArchive.prepare(fixture.lock, fixture.zip, run);
        Files.writeString(run.resolve("plugins/extra.jar"), "extra");
        assertThatThrownBy(() -> SdkRuntimeArchive.verifyBaseline(fixture.lock, run))
                .isInstanceOf(IOException.class).hasMessageContaining("UNEXPECTED_PLUGIN");
    }

    @Test
    @DisplayName("流复制接受精确预算，超出预算或总时限即失败")
    void streamingBudgetsUseActualBytes() throws Exception {
        Path output = Files.createFile(temp.resolve("stream"));
        byte[] input = new byte[65_537];
        assertThat(SdkRuntimeArchive.copy(new ByteArrayInputStream(input), output, input.length, Long.MAX_VALUE))
                .isEqualTo(input.length);
        assertThatThrownBy(() -> SdkRuntimeArchive.copy(new ByteArrayInputStream(input), output,
                input.length - 1, Long.MAX_VALUE)).isInstanceOf(IOException.class).hasMessageContaining("COPY_LIMIT");
        assertThatThrownBy(() -> SdkRuntimeArchive.copy(new ByteArrayInputStream(input), output,
                input.length, System.nanoTime() - 1)).isInstanceOf(IOException.class).hasMessageContaining("COPY_LIMIT");
    }

    private Fixture fixture() throws Exception {
        String version = SdkVersion.VERSION;
        String hostFile = "PixivDownload-" + version + ".jar";
        String pluginFile = "plugins/example-" + version + ".jar";
        byte[] host = bytes("fixed-host");
        byte[] plugin = bytes("fixed-plugin");
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(hostFile, host);
        entries.put(pluginFile, plugin);
        entries.put(pluginFile + ".sig", bytes("{}"));
        entries.put(pluginFile + ".sha256", bytes("digest"));
        entries.put("plugins/provenance/example-" + version + ".jar.pixiv-plugin-provenance", bytes("{}"));
        byte[] manifest = SdkRuntimeLock.JSON.writeValueAsBytes(List.of(Map.of(
                "id", "example", "version", version, "file", pluginFile,
                "size", plugin.length, "sha256", digest(plugin), "signature", Map.of("formatVersion", 1))));
        entries.put("plugins-manifest.json", manifest);
        Path zip = zip("runtime.zip", entries);
        var archive = new SdkRuntimeLock.Artifact("runtime.zip", Files.size(zip), SdkRuntimeArchive.sha256(zip,
                SdkRuntimeArchive.MAX_ARCHIVE_BYTES));
        var runtime = new SdkRuntimeLock.Runtime(version, "b".repeat(40), List.of("windows-x64", "linux-x64"),
                "https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/" + SdkVersion.releaseId() + "/runtime.zip",
                archive, new SdkRuntimeLock.Artifact(hostFile, host.length, digest(host)),
                new SdkRuntimeLock.Artifact("plugins-manifest.json", manifest.length, digest(manifest)));
        return new Fixture(new SdkRuntimeLock(version, SdkVersion.releaseId(), "a".repeat(40), runtime), zip);
    }

    private Path zip(String filename, Map<String, byte[]> entries) throws Exception {
        Path file = temp.resolve(filename);
        try (var zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(SdkRuntimeLock lock, Path zip) {
    }
}
