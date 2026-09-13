package top.sywyar.pixivdownload.sdk.community.scan;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageFormat;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.review.RiskReport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** 从同一份冻结包生成调用证据；归档安全准入继续由安装器的统一校验器负责。 */
public final class PluginRiskScanner {
    public record Execution(String runId, long runAttempt, String headSha, String sourceCommit) { }
    public record Component(String archivePath, long size, String sha256, RiskReport.Origin origin) { }
    public record Result(RiskReport report, List<Evidence> evidence, List<Component> components) {
        public Result { evidence = List.copyOf(evidence); components = List.copyOf(components); }
    }
    private record CallEvidence(String packageSha256, String archivePath, String classSha256,
                                BytecodeRiskScanner.Call call) { }

    private final String packageSha256;
    private final PluginPackageInspection inspection;
    private final int maximumReportBytes;
    private final Set<String> pluginClasses;
    private final List<RiskReport.Observation> observations = new ArrayList<>();
    private final List<RiskReport.Finding> findings = new ArrayList<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Component> components = new ArrayList<>();
    private long evidenceBytes;
    private String failure;

    private PluginRiskScanner(String sha256, PluginPackageInspection inspection, int maximumReportBytes, Set<String> pluginClasses) {
        this.packageSha256 = sha256;
        this.inspection = inspection;
        this.maximumReportBytes = maximumReportBytes;
        this.pluginClasses = Set.copyOf(pluginClasses);
    }

    /** 执行身份由平台核实；大小预算由受保护调用方提供，不读取投稿配置或 JVM 覆盖值。 */
    public static Result scan(Path artifact, String expectedSha256, Execution execution,
                              PluginPackageLimits limits, int maximumReportBytes, Set<String> pluginClasses) throws IOException {
        if (maximumReportBytes <= 0 || limits.maxArchiveBytes() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid scan budget");
        }
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new ContractException("PATH_UNSAFE", "artifact");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(artifact, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes((int) limits.maxArchiveBytes() + 1);
        }
        if (bytes.length > limits.maxArchiveBytes()) throw new ContractException("LIMIT_EXCEEDED", "artifact");
        if (!CommunityJson.sha256(bytes).equals(expectedSha256)) throw new ContractException("HASH_MISMATCH", "artifact");
        String extension = artifact.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") ? ".jar" : ".zip";
        var snapshot = Files.createTempFile("community-scan-", extension);
        try {
            Files.write(snapshot, bytes);
            PluginPackageVerifier.verify(snapshot, limits);
            var inspection = PluginPackageReader.inspect(snapshot, limits);
            var scanner = new PluginRiskScanner(expectedSha256, inspection, maximumReportBytes, pluginClasses);
            scanner.components.add(new Component("package" + extension, bytes.length, expectedSha256, RiskReport.Origin.PLUGIN));
            scanner.archive(bytes, "", RiskReport.Origin.PLUGIN, true);
            var report = new RiskReport(1, scanner.failure == null ? RiskReport.Status.COMPLETE : RiskReport.Status.INCOMPLETE,
                    BytecodeRiskScanner.VERSION, BytecodeRiskScanner.rulesSha256(), execution.runId,
                    execution.runAttempt, execution.headSha, execution.sourceCommit, expectedSha256,
                    scanner.observations, scanner.findings, scanner.failure);
            // 经唯一 Schema 回读，防止执行器生成无法供受保护审核消费的报告。
            CommunityJson.decode("riskReport", CommunityJson.encode(report), maximumReportBytes, RiskReport.class);
            return new Result(report, scanner.evidence, scanner.components);
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    private void archive(byte[] bytes, String prefix, RiskReport.Origin origin, boolean outer) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                boolean rootJar = outer && name.equals(inspection.innerJarEntry());
                boolean library = origin != RiskReport.Origin.DEPENDENCY && lower.startsWith("lib/")
                        && lower.endsWith(".jar") && name.indexOf('/', 4) < 0;
                boolean classFile = name.endsWith(".class") && (!outer
                        || inspection.format() != PluginPackageFormat.EXPLODED_DIRECTORY || name.startsWith("classes/"));
                if (!rootJar && !library && !classFile) continue;
                // 上游已完整校验同一冻结字节的每项和累计解压预算。
                byte[] content = zip.readAllBytes();
                String location = prefix + name;
                if (rootJar || library) {
                    var nestedOrigin = library ? RiskReport.Origin.DEPENDENCY : RiskReport.Origin.PLUGIN;
                    components.add(new Component(location, content.length, CommunityJson.sha256(content), nestedOrigin));
                    archive(content, location + "!/", nestedOrigin, false);
                } else {
                    scanClass(content, location, origin);
                }
            }
        }
    }

    private void scanClass(byte[] bytes, String archivePath, RiskReport.Origin origin) {
        List<BytecodeRiskScanner.Call> calls;
        try {
            calls = BytecodeRiskScanner.scan(bytes);
        } catch (RuntimeException invalidClass) {
            if (failure == null) failure = "CLASS_PARSE_FAILED: " + archivePath;
            return;
        }
        String classSha = CommunityJson.sha256(bytes);
        // 根包也可能含 shade 后的依赖；只有与实际编译输出字节相同才自动归属插件。
        // 无归属证据仍保存直接调用，不据此产生缺报或提高审核要求。
        if (origin == RiskReport.Origin.PLUGIN && !pluginClasses.contains(classSha)) origin = RiskReport.Origin.UNKNOWN;
        for (var call : calls) {
            byte[] proof = CommunityJson.encode(new CallEvidence(packageSha256, archivePath, classSha, call));
            evidenceBytes += proof.length;
            if (evidenceBytes > maximumReportBytes) throw new ContractException("LIMIT_EXCEEDED", "scanEvidence");
            String id = CommunityJson.sha256(proof);
            var reference = Reference.of("reviews/evidence/" + id + ".json", proof);
            evidence.add(new Evidence(reference, proof));
            var location = new RiskReport.Location(archivePath, call.className(), call.methodName(),
                    call.methodDescriptor(), call.bytecodeOffset(), null, null);
            observations.add(new RiskReport.Observation(id, call.signal(), origin, location, List.of(reference)));
            if (origin == RiskReport.Origin.PLUGIN && !inspection.descriptor().riskDeclaration().signals().contains(call.signal())) {
                findings.add(new RiskReport.Finding(id, call.ruleId(), call.signal(), RiskReport.Kind.DECLARATION_MISSING,
                        List.of(id), List.of(reference)));
            }
        }
    }
}
