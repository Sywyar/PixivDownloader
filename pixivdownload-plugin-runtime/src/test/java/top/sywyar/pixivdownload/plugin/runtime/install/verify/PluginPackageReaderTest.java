package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageFormat;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

@DisplayName("外置插件包读取：布局识别、描述符映射与非法包拒绝")
class PluginPackageReaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("解压目录形态（根 plugin.properties + classes/）：识别为 EXPLODED_DIRECTORY 并映射包级描述符")
    void readsExplodedDirectoryLayout() {
        Path zip = PluginPackageFixtures.explodedZip(tempDir.resolve("p.zip"),
                "ext-stats", "1.2.0", "1.0", "com.example.ExtStatsPlugin");

        PluginPackageInspection inspection = PluginPackageReader.inspect(zip);

        assertThat(inspection.format()).isEqualTo(PluginPackageFormat.EXPLODED_DIRECTORY);
        assertThat(inspection.innerJarEntry()).isNull();
        assertThat(inspection.containsPrivateLibraries()).isFalse();
        PluginDescriptor descriptor = inspection.descriptor();
        assertThat(descriptor.id()).isEqualTo("ext-stats");
        assertThat(descriptor.sourcePluginId()).isEqualTo("ext-stats");
        assertThat(descriptor.version()).isEqualTo("1.2.0");
        assertThat(descriptor.pluginClass()).isEqualTo("com.example.ExtStatsPlugin");
        assertThat(descriptor.kind()).isEqualTo(PluginKind.FEATURE);
        assertThat(descriptor.externalValidationErrors()).isEmpty();
        assertThat(descriptor.isApiCompatible()).isTrue();
    }

    @Test
    @DisplayName("单 jar 形态（zip 内一个根 jar，描述符在 jar 内）：识别为 SINGLE_JAR、记录 innerJarEntry")
    void readsSingleJarInsideZip() {
        Path zip = PluginPackageFixtures.singleJarZip(tempDir.resolve("p.zip"),
                "ext-plugin.jar", "ext-dup", "2.0.0", "1.0", "com.example.DupPlugin");

        PluginPackageInspection inspection = PluginPackageReader.inspect(zip);

        assertThat(inspection.format()).isEqualTo(PluginPackageFormat.SINGLE_JAR);
        assertThat(inspection.innerJarEntry()).isEqualTo("ext-plugin.jar");
        assertThat(inspection.containsPrivateLibraries()).isFalse();
        assertThat(inspection.descriptor().id()).isEqualTo("ext-dup");
        assertThat(inspection.descriptor().version()).isEqualTo("2.0.0");
        assertThat(inspection.descriptor().externalValidationErrors()).isEmpty();
    }

    @Test
    @DisplayName("上传物本身是插件 jar（含 plugin.properties）：识别为 SINGLE_JAR、innerJarEntry 为 null")
    void readsBareJar() {
        Path jar = PluginPackageFixtures.bareJar(tempDir.resolve("p.jar"),
                "ext-x", "1.0.0", null, "com.example.X");

        PluginPackageInspection inspection = PluginPackageReader.inspect(jar);

        assertThat(inspection.format()).isEqualTo(PluginPackageFormat.SINGLE_JAR);
        assertThat(inspection.innerJarEntry()).isNull();
        assertThat(inspection.containsPrivateLibraries()).isFalse();
        assertThat(inspection.descriptor().id()).isEqualTo("ext-x");
    }

    @Test
    @DisplayName("上传物本身是带私有依赖的插件 jar：识别 SINGLE_JAR 并标记 containsPrivateLibraries")
    void readsBareJarWithPrivateLibraries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("lib/flatlaf-3.5.4.jar", PluginPackageFixtures.bytes("fake-flatlaf"));
        entries.put("lib/nested/ignored.jar", PluginPackageFixtures.bytes("not-a-direct-lib"));
        Path jar = tempDir.resolve("with-private-libs.jar");
        Files.write(jar, PluginPackageFixtures.pluginJarBytes(
                "gui-theme", "1.0.0", null, "com.example.ThemePlugin", entries));

        PluginPackageInspection inspection = PluginPackageReader.inspect(jar);

        assertThat(inspection.format()).isEqualTo(PluginPackageFormat.SINGLE_JAR);
        assertThat(inspection.innerJarEntry()).isNull();
        assertThat(inspection.containsPrivateLibraries()).isTrue();
        assertThat(inspection.descriptor().id()).isEqualTo("gui-theme");
    }

    @Test
    @DisplayName("依赖解析：plugin.dependencies 映射为依赖载体（含可选标记 ? 与版本要求 @）")
    void parsesDependencies() {
        String properties = PluginPackageReader.KEY_ID + "=ext\n"
                + PluginPackageReader.KEY_VERSION + "=1.0.0\n"
                + PluginPackageReader.KEY_CLASS + "=com.example.P\n"
                + PluginPackageReader.KEY_DEPENDENCIES + "=novel@1.0, gallery?\n";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageReader.PLUGIN_PROPERTIES, properties.getBytes(StandardCharsets.UTF_8));
        entries.put("classes/Marker.class", PluginPackageFixtures.bytes("x"));
        Path zip = tempDir.resolve("deps.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        PluginDescriptor descriptor = PluginPackageReader.inspect(zip).descriptor();

        assertThat(descriptor.dependencies()).hasSize(2);
        assertThat(descriptor.dependencies().get(0).pluginId()).isEqualTo("novel");
        assertThat(descriptor.dependencies().get(0).optional()).isFalse();
        assertThat(descriptor.dependencies().get(0).versionSupport()).isEqualTo("1.0");
        assertThat(descriptor.dependencies().get(1).pluginId()).isEqualTo("gallery");
        assertThat(descriptor.dependencies().get(1).optional()).isTrue();
    }

    @Test
    @DisplayName("lib/ 下的 jar 不算根插件候选：仍识别为解压目录形态")
    void libJarsAreNotRootCandidates() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageReader.PLUGIN_PROPERTIES,
                PluginPackageFixtures.bytes(PluginPackageFixtures.pluginProperties("ext", "1.0.0", null, "com.example.P")));
        entries.put("lib/dep1.jar", new byte[]{1, 2});
        entries.put("lib/dep2.jar", new byte[]{3, 4});
        Path zip = tempDir.resolve("withlibs.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        PluginPackageInspection inspection = PluginPackageReader.inspect(zip);

        assertThat(inspection.format()).isEqualTo(PluginPackageFormat.EXPLODED_DIRECTORY);
        assertThat(inspection.containsPrivateLibraries()).isTrue();
        assertThat(inspection.descriptor().id()).isEqualTo("ext");
    }

    @Test
    @DisplayName("空包（无任何 entry）：抛 EMPTY")
    void rejectsEmptyZip() {
        Path zip = tempDir.resolve("empty.zip");
        PluginPackageFixtures.writeZip(zip, new LinkedHashMap<>());
        assertReason(zip, PluginPackageException.Reason.EMPTY);
    }

    @Test
    @DisplayName("缺描述符（有文件但无 plugin.properties、无根 jar）：抛 NO_DESCRIPTOR")
    void rejectsMissingDescriptor() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("readme.txt", PluginPackageFixtures.bytes("hello"));
        entries.put("classes/Marker.class", PluginPackageFixtures.bytes("y"));
        Path zip = tempDir.resolve("nodesc.zip");
        PluginPackageFixtures.writeZip(zip, entries);
        assertReason(zip, PluginPackageException.Reason.NO_DESCRIPTOR);
    }

    @Test
    @DisplayName("布局歧义（多个根插件 jar 候选）：抛 AMBIGUOUS")
    void rejectsMultipleRootJars() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.jar", PluginPackageFixtures.pluginJarBytes("a", "1.0.0", null, "com.example.A"));
        entries.put("b.jar", PluginPackageFixtures.pluginJarBytes("b", "1.0.0", null, "com.example.B"));
        Path zip = tempDir.resolve("ambi.zip");
        PluginPackageFixtures.writeZip(zip, entries);
        assertReason(zip, PluginPackageException.Reason.AMBIGUOUS);
    }

    @Test
    @DisplayName("布局歧义（根 plugin.properties 与根 jar 并存）：抛 AMBIGUOUS")
    void rejectsPropsCoexistingWithRootJar() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageReader.PLUGIN_PROPERTIES,
                PluginPackageFixtures.bytes(PluginPackageFixtures.pluginProperties("ext", "1.0.0", null, "com.example.P")));
        entries.put("rogue.jar", PluginPackageFixtures.pluginJarBytes("rogue", "1.0.0", null, "com.example.R"));
        Path zip = tempDir.resolve("ambi2.zip");
        PluginPackageFixtures.writeZip(zip, entries);
        assertReason(zip, PluginPackageException.Reason.AMBIGUOUS);
    }

    @Test
    @DisplayName("单根 jar 但 jar 内无 plugin.properties：抛 NO_DESCRIPTOR")
    void rejectsRootJarWithoutDescriptor() {
        Map<String, byte[]> innerJar = new LinkedHashMap<>();
        innerJar.put("com/example/X.class", PluginPackageFixtures.bytes("c"));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.jar", PluginPackageFixtures.zipBytes(innerJar));
        Path zip = tempDir.resolve("nojardesc.zip");
        PluginPackageFixtures.writeZip(zip, entries);
        assertReason(zip, PluginPackageException.Reason.NO_DESCRIPTOR);
    }

    @Test
    @DisplayName("非 zip 内容的文件：抛 MALFORMED")
    void rejectsMalformed() throws IOException {
        Path zip = tempDir.resolve("bad.zip");
        Files.writeString(zip, "this is not a zip", StandardCharsets.UTF_8);
        assertReason(zip, PluginPackageException.Reason.MALFORMED);
    }

    @Test
    @DisplayName("描述符超出读取上限：抛 TOO_LARGE（按实际读取字节，不信 header）")
    void rejectsOversizedDescriptor() {
        Path zip = PluginPackageFixtures.explodedZip(tempDir.resolve("bigdesc.zip"),
                "ext", "1.0.0", null, "com.example.SomewhatLongPluginClassName");
        // 描述符上限 8 字节，真实 plugin.properties 数十字节 → 超限
        PluginPackageLimits tiny = new PluginPackageLimits(
                PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES,
                PluginPackageLimits.DEFAULT_MAX_ENTRIES,
                PluginPackageLimits.DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
                PluginPackageLimits.DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
                8,
                PluginPackageLimits.DEFAULT_MAX_COMPRESSION_RATIO);

        assertThatThrownBy(() -> PluginPackageReader.inspect(zip, tiny))
                .isInstanceOf(PluginPackageException.class)
                .satisfies(e -> assertThat(((PluginPackageException) e).reason())
                        .isEqualTo(PluginPackageException.Reason.TOO_LARGE));
    }

    private void assertReason(Path packagePath, PluginPackageException.Reason reason) {
        assertThatThrownBy(() -> PluginPackageReader.inspect(packagePath))
                .isInstanceOf(PluginPackageException.class)
                .satisfies(e -> assertThat(((PluginPackageException) e).reason()).isEqualTo(reason));
    }
}
