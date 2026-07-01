package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PluginInstallService} 单测：上传本地包 → 临时落盘 → 委托真实 {@link ExternalPluginInstaller} 安装 →
 * 结构化 {@link PluginInstallReport}（结果分类、落盘语义、{@code effectiveAfterRestart} 重启生效边界、依赖诊断）。
 * 用真实安装器（POJO，注入 {@code @TempDir} 安装目录）而非桩，端到端覆盖「校验 + 落盘 + 重复 / 升级 / 降级」语义。
 */
@DisplayName("PluginInstallService 本地插件包安装后端服务")
class PluginInstallServiceTest {

    @TempDir
    Path home;
    private Path pluginsDir;
    private PluginInstallService service;

    @BeforeEach
    void setUp() {
        pluginsDir = home.resolve("plugins");
        service = new PluginInstallService(new ExternalPluginInstaller(pluginsDir));
    }

    @Test
    @DisplayName("上传合法解压目录形态 .zip：INSTALLED，accepted 且 effectiveAfterRestart（重启后生效），规范落盘")
    void installsExplodedZip() {
        PluginInstallReport report = service.install(explodedUpload("upload.zip", "ext-demo", "1.0.0", null, null), false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.accepted()).isTrue();
        assertThat(report.effectiveAfterRestart()).isTrue();
        assertThat(report.pluginId()).isEqualTo("ext-demo");
        assertThat(report.version()).isEqualTo("1.0.0");
        assertThat(pluginFiles()).containsExactly("ext-demo-1.0.0.zip");
    }

    @Test
    @DisplayName("上传本体即插件 .jar：INSTALLED，按描述符 id/version 规范命名落盘（与上传文件名无关）")
    void installsBareJarByDescriptorName() {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "whatever-name.jar", "application/java-archive",
                pluginJarBytes("ext-jar", "2.3.4", null, "com.example.JarPlugin"));

        PluginInstallReport report = service.install(upload, false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.pluginId()).isEqualTo("ext-jar");
        assertThat(pluginFiles()).containsExactly("ext-jar-2.3.4.jar");
    }

    @Test
    @DisplayName("同 id 同版本重复上传：DUPLICATE，幂等不产生第二个副本")
    void duplicateIsIdempotent() {
        service.install(explodedUpload("a.zip", "ext", "1.0.0", null, null), false);
        PluginInstallReport again = service.install(explodedUpload("a.zip", "ext", "1.0.0", null, null), false);

        assertThat(again.outcome()).isEqualTo(PluginInstallOutcome.DUPLICATE);
        assertThat(again.accepted()).isTrue();
        assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip");
    }

    @Test
    @DisplayName("高版本覆盖低版本：UPGRADED，回显被取代旧版本，仅留新版本")
    void higherVersionUpgrades() {
        service.install(explodedUpload("a.zip", "ext", "1.0.0", null, null), false);
        PluginInstallReport upgrade = service.install(explodedUpload("a.zip", "ext", "1.1.0", null, null), false);

        assertThat(upgrade.outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(upgrade.previousVersion()).isEqualTo("1.0.0");
        assertThat(pluginFiles()).containsExactly("ext-1.1.0.zip");
    }

    @Test
    @DisplayName("低版本覆盖高版本：默认 DOWNGRADE_REJECTED（not accepted），安装目录不变")
    void lowerVersionRejectedByDefault() {
        service.install(explodedUpload("a.zip", "ext", "2.0.0", null, null), false);
        PluginInstallReport downgrade = service.install(explodedUpload("a.zip", "ext", "1.0.0", null, null), false);

        assertThat(downgrade.outcome()).isEqualTo(PluginInstallOutcome.DOWNGRADE_REJECTED);
        assertThat(downgrade.accepted()).isFalse();
        assertThat(downgrade.effectiveAfterRestart()).isFalse();
        assertThat(pluginFiles()).containsExactly("ext-2.0.0.zip");
    }

    @Test
    @DisplayName("低版本覆盖高版本 + 显式允许降级（force）：DOWNGRADED，高版本被移除")
    void lowerVersionAllowedWithForce() {
        service.install(explodedUpload("a.zip", "ext", "2.0.0", null, null), false);
        PluginInstallReport downgrade = service.install(explodedUpload("a.zip", "ext", "1.0.0", null, null), true);

        assertThat(downgrade.outcome()).isEqualTo(PluginInstallOutcome.DOWNGRADED);
        assertThat(downgrade.previousVersion()).isEqualTo("2.0.0");
        assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip");
    }

    @Test
    @DisplayName("requires 高于核心 API：REJECTED_INCOMPATIBLE，零落盘")
    void rejectsIncompatible() {
        String requires = (PluginApiVersion.MAJOR + 1) + ".0";
        PluginInstallReport report = service.install(
                explodedUpload("a.zip", "ext", "1.0.0", requires, null), false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INCOMPATIBLE);
        assertThat(report.accepted()).isFalse();
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("上传不受支持的扩展名（.txt）：REJECTED_MALFORMED，零落盘（仅取扩展名、绝不用上传名做路径）")
    void rejectsUnsupportedExtension() {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "../../evil.txt", "text/plain", explodedZipBytes("ext", "1.0.0", null, null));

        PluginInstallReport report = service.install(upload, false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_MALFORMED);
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("空上传 / null 上传：REJECTED_EMPTY，零落盘，不抛异常")
    void rejectsEmptyOrNullUpload() {
        MockMultipartFile empty = new MockMultipartFile("file", "x.zip", "application/zip", new byte[0]);

        assertThat(service.install(empty, false).outcome()).isEqualTo(PluginInstallOutcome.REJECTED_EMPTY);
        assertThat(service.install(null, false).outcome()).isEqualTo(PluginInstallOutcome.REJECTED_EMPTY);
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("依赖诊断：非可选依赖里非内置且未安装者列入 unsatisfiedDependencies；内置依赖不列入；声明依赖全部投影")
    void diagnosesUnsatisfiedDependencies() {
        // download-workbench 是内置（满足）；ghost-plugin 既非内置也未安装（不满足，列入诊断）。
        PluginInstallReport report = service.install(
                explodedUpload("a.zip", "ext", "1.0.0", null, "download-workbench,ghost-plugin"), false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.dependencies()).extracting(PluginManagementService.PluginDependencyView::pluginId)
                .containsExactlyInAnyOrder("download-workbench", "ghost-plugin");
        assertThat(report.unsatisfiedDependencies()).containsExactly("ghost-plugin");
    }

    @Test
    @DisplayName("上传包超出资源上限（entry 数）：REJECTED_TOO_LARGE，零落盘")
    void rejectsOversizedUpload() {
        // 注入一个 entry 上限收紧到 1 的安装器；解压目录形态包有 3 个 entry → 超限
        PluginPackageLimits tight = new PluginPackageLimits(
                PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES,
                1,
                PluginPackageLimits.DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
                PluginPackageLimits.DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
                PluginPackageLimits.DEFAULT_MAX_DESCRIPTOR_BYTES,
                PluginPackageLimits.DEFAULT_MAX_COMPRESSION_RATIO);
        PluginInstallService limited = new PluginInstallService(new ExternalPluginInstaller(pluginsDir, tight));

        PluginInstallReport report = limited.install(explodedUpload("big.zip", "ext", "1.0.0", null, null), false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_TOO_LARGE);
        assertThat(report.accepted()).isFalse();
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("依赖诊断：可选依赖缺失不列入 unsatisfiedDependencies（加载期解析、不阻断安装）")
    void optionalMissingDependencyNotFlagged() {
        PluginInstallReport report = service.install(
                explodedUpload("a.zip", "ext", "1.0.0", null, "ghost-opt?"), false);

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.dependencies()).hasSize(1);
        assertThat(report.dependencies().get(0).optional()).isTrue();
        assertThat(report.unsatisfiedDependencies()).isEmpty();
    }

    // ---------- helpers（内联构造插件包字节，不依赖 plugin-runtime 测试夹具）----------

    private MockMultipartFile explodedUpload(String filename, String id, String version,
                                             String requires, String dependencies) {
        return new MockMultipartFile("file", filename, "application/zip",
                explodedZipBytes(id, version, requires, dependencies));
    }

    /** 解压目录形态包字节：根 {@code plugin.properties} + {@code classes/} 负载。 */
    private static byte[] explodedZipBytes(String id, String version, String requires, String dependencies) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.properties", props(id, version, requires, "com.example.Plugin", dependencies));
        entries.put("classes/", new byte[0]);
        entries.put("classes/Marker.class", "fake-class".getBytes(StandardCharsets.UTF_8));
        return zipBytes(entries);
    }

    /** 含 {@code plugin.properties} 的插件 jar 字节（上传物本体即 jar 的形态）。 */
    private static byte[] pluginJarBytes(String id, String version, String requires, String pluginClass) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.properties", props(id, version, requires, pluginClass, null));
        entries.put("com/example/Marker.class", "fake-class".getBytes(StandardCharsets.UTF_8));
        return zipBytes(entries);
    }

    /** 拼一份 PF4J {@code plugin.properties}（参数为 {@code null} 时跳过该行）。 */
    private static byte[] props(String id, String version, String requires, String pluginClass, String dependencies) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "plugin.id", id);
        appendLine(sb, "plugin.version", version);
        appendLine(sb, "plugin.class", pluginClass);
        appendLine(sb, "plugin.requires", requires);
        appendLine(sb, "plugin.dependencies", dependencies);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendLine(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(key).append('=').append(value).append('\n');
        }
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                byte[] content = entry.getValue();
                if (content.length > 0) {
                    zos.write(content);
                }
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private java.util.List<String> pluginFiles() {
        if (!Files.isDirectory(pluginsDir)) {
            return java.util.List.of();
        }
        try (var stream = Files.list(pluginsDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .filter(n -> n.endsWith(".jar") || n.endsWith(".zip"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
