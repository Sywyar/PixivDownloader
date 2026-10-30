package top.sywyar.pixivdownload.sdk.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.asm.Opcodes;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 独立契约作者提供两个 JAR；只读取 classfile，不加载或执行其中的类。 */
public final class ContractEvolution {
    private ContractEvolution() { }

    /** 单项结构变化；不包含字段常量、代码或业务载荷。 */
    public record Finding(String code, String type, String member) { }
    /** 三个兼容维度分开报告；静态表面检查不证明业务行为兼容。 */
    public record Report(String baselineSha256, String candidateSha256, String sourceCompatibility,
                         String binaryLinkage, String newHostOldImplementation, String behaviorCompatibility,
                         List<Finding> findings) { }

    /** 比较作者提供的契约，不要求中央业务包名单。 */
    public static Report compare(Path baseline, Path candidate) throws IOException {
        String baselineHash = hash(baseline), candidateHash = hash(candidate);
        var before = PluginSdkUsage.read(List.of(baseline));
        var after = PluginSdkUsage.read(List.of(candidate));
        if (before.values().stream().noneMatch(shape -> visible(shape.access))
                || after.values().stream().noneMatch(shape -> visible(shape.access))) throw new IOException("SDK_EVOLUTION_NO_PUBLIC_TYPES");
        if (!baselineHash.equals(hash(baseline)) || !candidateHash.equals(hash(candidate))) throw new IOException("SDK_EVOLUTION_INPUT_CHANGED");
        var changes = new ArrayList<Finding>();
        boolean binaryBreak = false, sourceBreak = false, implementationBreak = false;
        for (var entry : new TreeMap<>(before).entrySet()) {
            String name = entry.getKey(); var previous = entry.getValue();
            if (!visible(previous.access)) continue;
            var current = after.get(name);
            if (current == null || !visible(current.access)) {
                add(changes, "TYPE_REMOVED_OR_HIDDEN", name, ""); binaryBreak = sourceBreak = true; continue;
            }
            int kind = Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_RECORD;
            if ((previous.access & kind) != (current.access & kind)
                    || (current.access & Opcodes.ACC_FINAL) != 0 && (previous.access & Opcodes.ACC_FINAL) == 0
                    || (current.access & Opcodes.ACC_ABSTRACT) != 0 && (previous.access & Opcodes.ACC_ABSTRACT) == 0
                    || current.version > Opcodes.V17
                    || !new HashSet<>(current.parents).equals(new HashSet<>(previous.parents))
                    || !current.permitted.equals(previous.permitted)) {
                add(changes, "TYPE_HIERARCHY_OR_KIND_CHANGED", name, ""); binaryBreak = sourceBreak = true;
            }
            if (!Objects.equals(previous.signature, current.signature)) add(changes, "GENERIC_SIGNATURE_CHANGED", name, "");
            for (String member : new TreeSet<>(previous.members)) {
                String owner = PluginSdkUsage.resolve(after, name, member, new HashSet<>());
                var oldMember = previous.details.get(member);
                var newMember = owner == null ? null : after.get(owner).details.get(member);
                if (newMember == null || !visible(newMember.access())
                        || (oldMember.access() & Opcodes.ACC_PUBLIC) != 0 && (newMember.access() & Opcodes.ACC_PUBLIC) == 0) {
                    add(changes, "MEMBER_REMOVED_OR_HIDDEN", name, member); binaryBreak = sourceBreak = true; continue;
                }
                if ((oldMember.access() & Opcodes.ACC_STATIC) != (newMember.access() & Opcodes.ACC_STATIC)
                        || (newMember.access() & Opcodes.ACC_FINAL) != 0 && (oldMember.access() & Opcodes.ACC_FINAL) == 0
                        || (newMember.access() & Opcodes.ACC_ABSTRACT) != 0 && (oldMember.access() & Opcodes.ACC_ABSTRACT) == 0) {
                    add(changes, "MEMBER_DISPATCH_CHANGED", name, member); binaryBreak = sourceBreak = true;
                }
                if (!Objects.equals(oldMember.signature(), newMember.signature())) add(changes, "GENERIC_SIGNATURE_CHANGED", name, member);
                if (!new HashSet<>(oldMember.exceptions()).equals(new HashSet<>(newMember.exceptions()))) add(changes, "THROWS_CHANGED", name, member);
                if (!Objects.equals(oldMember.constant(), newMember.constant())) add(changes, "INLINED_CONSTANT_CHANGED", name, member);
            }
            for (String member : new TreeSet<>(current.members)) {
                if (PluginSdkUsage.resolve(before, name, member, new HashSet<>()) != null) continue;
                int access = current.details.get(member).access();
                if (member.contains("(") && (access & Opcodes.ACC_ABSTRACT) != 0) {
                    // JLS 13.5.4 允许新增接口抽象方法；旧实现不能因此获得新行为。
                    add(changes, "ABSTRACT_METHOD_ADDED", name, member); sourceBreak = implementationBreak = true;
                    if ((current.access & Opcodes.ACC_INTERFACE) == 0) binaryBreak = true;
                } else add(changes, member.contains("(") ? "METHOD_ADDED_REQUIRES_SOURCE_AND_DISPATCH_REVIEW" : "FIELD_ADDED", name, member);
            }
        }
        for (var entry : new TreeMap<>(after).entrySet())
            if (!before.containsKey(entry.getKey()) && visible(entry.getValue().access)) add(changes, "TYPE_ADDED", entry.getKey(), "");
        return new Report(baselineHash, candidateHash, sourceBreak ? "BREAKING_FOR_EXISTING_SOURCE"
                : changes.isEmpty() ? "NO_STRUCTURAL_CHANGE" : "REQUIRES_RECOMPILATION_CHECK",
                binaryBreak ? "POTENTIALLY_BREAKING" : "NO_DETECTED_BREAK",
                implementationBreak ? "NEW_ABSTRACT_METHOD_UNIMPLEMENTED" : "REQUIRES_RUNTIME_CHECK",
                "NOT_ASSESSED", List.copyOf(changes));
    }

    /** 输出完整 UTF-8 报告；检测到破坏时返回 false，仍保留报告供作者定位。 */
    public static boolean write(Path baseline, Path candidate, Path output) throws IOException {
        Report report = compare(baseline, candidate);
        Path target = output.toAbsolutePath().normalize();
        if (target.equals(baseline.toAbsolutePath().normalize()) || target.equals(candidate.toAbsolutePath().normalize())
                || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SDK_EVOLUTION_OUTPUT_EXISTS");
        Files.createDirectories(target.getParent());
        byte[] bytes = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        if (bytes.length > 1024 * 1024) throw new IOException("SDK_EVOLUTION_REPORT_LIMIT");
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        return !report.binaryLinkage().equals("POTENTIALLY_BREAKING")
                && !report.sourceCompatibility().equals("BREAKING_FOR_EXISTING_SOURCE");
    }

    private static void add(List<Finding> changes, String code, String type, String member) throws IOException {
        if (changes.size() >= 4096) throw new IOException("SDK_EVOLUTION_FINDING_LIMIT");
        changes.add(new Finding(code, type.replace('/', '.'), member));
    }
    private static boolean visible(int access) { return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0; }
    private static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 512L * 1024 * 1024) throw new IOException("SDK_EVOLUTION_INPUT_LIMIT");
        try (var input = Files.newInputStream(path)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[8192];
            for (int count; (count = input.read(bytes)) != -1;) hash.update(bytes, 0, count);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
