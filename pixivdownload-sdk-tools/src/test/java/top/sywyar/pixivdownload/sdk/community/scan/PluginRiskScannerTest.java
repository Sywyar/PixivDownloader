package top.sywyar.pixivdownload.sdk.community.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.review.RiskReport;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class PluginRiskScannerTest {
    @TempDir Path temporary;
    private static final int REPORT_BYTES = 32 * 1024 * 1024;
    private static final PluginRiskScanner.Execution RUN = new PluginRiskScanner.Execution("123", 1, "a".repeat(40), "b".repeat(40));

    @Test
    @DisplayName("真实编译包生成可复算证据，缺报阻断而已声明调用通过")
    void compiledPackage() throws Exception {
        byte[] code = compile();
        var result = scan(jar(code, "", false), ".jar");
        assertThat(result.report().status()).isEqualTo(RiskReport.Status.COMPLETE);
        assertThat(result.report().findings()).singleElement().satisfies(finding -> {
            assertThat(finding.signal()).isEqualTo("FILE_DELETE");
            assertThat(finding.ruleId()).isEqualTo("files-delete");
        });
        var found = result.report().observations().get(0);
        assertThat(found.location().className()).isEqualTo("sample/Probe");
        assertThat(found.location().methodName()).isEqualTo("remove");
        assertThat(found.location().bytecodeOffset()).isEqualTo(1);
        assertThat(result.evidence()).allSatisfy(value -> value.reference().verify(value.bytes()));
        var declared = scan(jar(code, "FILE_DELETE", false), ".jar");
        assertThat(declared.report().findings()).isEmpty();
        assertThat(declared.report().observations()).hasSize(1);
        var repeated = scan(jar(code, "", false), ".jar");
        assertThat(repeated.report()).isEqualTo(result.report());
        var sbom = PluginScanEvidence.sbom(result, REPORT_BYTES);
        sbom.reference().verify(sbom.bytes());
        var inventory = CommunityJson.strictTree(sbom.bytes(), REPORT_BYTES);
        assertThat(inventory.path("bomFormat").textValue()).isEqualTo("CycloneDX");
        assertThat(inventory.path("components").get(0).path("hashes").get(0).path("content").textValue())
                .isEqualTo(result.report().packageSha256());
        var delta = PluginScanEvidence.difference(declared.report(), result.report(), REPORT_BYTES);
        var difference = CommunityJson.strictTree(delta.bytes(), REPORT_BYTES);
        assertThat(difference.path("addedObservations").size()).isZero();
        assertThat(difference.path("removedObservations").size()).isZero();
        var first = PluginScanEvidence.difference(result.report(), null, REPORT_BYTES);
        assertThat(CommunityJson.strictTree(first.bytes(), REPORT_BYTES).path("addedObservations").size()).isEqualTo(1);
        assertThatThrownBy(() -> PluginScanEvidence.sbom(result, 1)).hasMessageContaining("LIMIT_EXCEEDED");
        Path withoutOrigin = temporary.resolve("without-origin.jar");
        Files.write(withoutOrigin, jar(code, "", false));
        var unknown = PluginRiskScanner.scan(withoutOrigin, CommunityJson.sha256(Files.readAllBytes(withoutOrigin)), RUN,
                PluginPackageLimits.defaults(), REPORT_BYTES, Set.of());
        assertThat(unknown.report().status()).isEqualTo(RiskReport.Status.COMPLETE);
        assertThat(unknown.report().observations()).singleElement().extracting(RiskReport.Observation::origin).isEqualTo(RiskReport.Origin.UNKNOWN);
        assertThat(unknown.report().findings()).isEmpty();
    }

    @Test
    @DisplayName("私有库调用单独归属，原生资源不算解析失败，支持既有三种包布局")
    void layoutsAndDependencies() throws Exception {
        byte[] code = compile();
        byte[] dependency = zip(Map.of("sample/Probe.class", code));
        byte[] artifact = zip(Map.of("plugin.properties", descriptor(""), "lib/helper.jar", dependency, "native/helper.dll", new byte[]{1, 2, 3}));
        var library = scan(artifact, ".jar");
        assertThat(library.report().status()).isEqualTo(RiskReport.Status.COMPLETE);
        assertThat(library.report().findings()).isEmpty();
        assertThat(library.report().observations()).singleElement().satisfies(value -> {
            assertThat(value.origin()).isEqualTo(RiskReport.Origin.DEPENDENCY);
            assertThat(value.location().archivePath()).isEqualTo("lib/helper.jar!/sample/Probe.class");
        });
        assertThat(library.components()).hasSize(2);
        var wrapped = scan(zip(Map.of("plugin.jar", jar(code, "", false))), ".zip");
        var exploded = scan(zip(Map.of("plugin.properties", descriptor(""), "classes/sample/Probe.class", code)), ".zip");
        assertThat(wrapped.report().findings()).hasSize(1);
        assertThat(exploded.report().findings()).hasSize(1);
    }

    @Test
    @DisplayName("必要字节码解析失败仍保留确定缺报，不用空报告掩盖失败")
    void incompleteKeepsFindings() throws Exception {
        var result = scan(jar(compile(), "", true), ".jar");
        assertThat(result.report().status()).isEqualTo(RiskReport.Status.INCOMPLETE);
        assertThat(result.report().failureReason()).contains("CLASS_PARSE_FAILED");
        assertThat(result.report().findings()).hasSize(1);
    }

    @Test
    @DisplayName("错摘要、不安全归档和证据预算超限都拒绝生成可准入报告")
    void inputBoundaries() throws Exception {
        byte[] bytes = jar(compile(), "", false);
        Path file = temporary.resolve("budget.jar");
        Files.write(file, bytes);
        assertThatThrownBy(() -> PluginRiskScanner.scan(file, "0".repeat(64), RUN, PluginPackageLimits.defaults(), REPORT_BYTES, Set.of()))
                .hasMessageContaining("HASH_MISMATCH");
        assertThatThrownBy(() -> PluginRiskScanner.scan(file, CommunityJson.sha256(bytes), RUN, PluginPackageLimits.defaults(), 128, Set.of()))
                .hasMessageContaining("LIMIT_EXCEEDED");
        assertThatThrownBy(() -> scan(zip(Map.of("plugin.properties", descriptor(""), "../escape", new byte[]{1})), ".jar"))
                .isInstanceOf(RuntimeException.class);
    }

    private PluginRiskScanner.Result scan(byte[] bytes, String extension) throws Exception {
        Path file = Files.createTempFile(temporary, "plugin-", extension);
        Files.write(file, bytes);
        var result = PluginRiskScanner.scan(file, CommunityJson.sha256(bytes), RUN, PluginPackageLimits.defaults(), REPORT_BYTES,
                Set.of(CommunityJson.sha256(Files.readAllBytes(temporary.resolve("sample/Probe.class")))));
        var values = result.evidence().stream().collect(Collectors.toMap(value -> value.reference().path(), value -> value));
        var descriptor = top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader.inspect(file).descriptor();
        result.report().validate(descriptor.riskDeclaration(), values);
        byte[] report = CommunityJson.encode(result.report());
        assertThat(RiskReport.read(new Evidence(top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference.of("scan.json", report), report), REPORT_BYTES))
                .isEqualTo(result.report());
        return result;
    }

    private byte[] compile() throws Exception {
        Path source = temporary.resolve("Probe.java");
        Files.writeString(source, """
                package sample;
                public class Probe {
                    public static final String UNUSED = "java.net.URL.openStream";
                    public static void remove(java.nio.file.Path path) throws java.io.IOException {
                        java.nio.file.Files.delete(path);
                    }
                }
                """, StandardCharsets.UTF_8);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-d", temporary.toString(), source.toString())).isZero();
        return Files.readAllBytes(temporary.resolve("sample/Probe.class"));
    }

    private static byte[] descriptor(String signals) {
        return ("plugin.id=sample\nplugin.version=7.8.9\nplugin.class=sample.Probe\npixiv.execution-mode=host-process-full-trust\n"
                + (signals.isEmpty() ? "" : "pixiv.risk-signals=" + signals + "\n")).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(byte[] code, String signals, boolean broken) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("plugin.properties", descriptor(signals));
        entries.put("sample/Probe.class", code);
        if (broken) entries.put("sample/Broken.class", new byte[]{1, 2, 3});
        return zip(entries);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                var item = new ZipEntry(entry.getKey());
                item.setTime(0);
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
