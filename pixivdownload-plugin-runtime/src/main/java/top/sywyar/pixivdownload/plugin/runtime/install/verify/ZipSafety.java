package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * ZIP entry 名安全工具：拒绝路径逃逸、跨文件系统歧义和规范化后重名，并提供安全的单 entry 解压。
 *
 * <p>所有归档消费者复用 {@link #requireUniqueEntryName(String, Set)}：分隔符统一后按 Unicode NFKC、大小写与
 * Windows 文件名规则生成唯一键；任一不安全名称或键冲突都拒绝整包。这样 verifier、读取器和 runtime 物化器不会
 * 分别看到不同的归档内容。
 *
 * <p>安装器自身<b>从不</b>用不可信 entry 名拼接磁盘路径——单 jar 形态解压时目标文件名是安装器规范生成的
 * （{@code {id}-{version}.jar}），entry 名仅用于在 zip 内定位源；解压目录形态则原样复制已校验过的整 zip。因此越界写
 * 在安装阶段被彻底排除。
 */
public final class ZipSafety {

    /** 盘符根（如 {@code C:}、{@code C:/x}、{@code C:x}）——分隔符已统一为 {@code /} 后匹配。 */
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("(?s)^[A-Za-z]:.*");
    private static final Pattern WINDOWS_RESERVED = Pattern.compile(
            "^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$");

    private ZipSafety() {
    }

    /**
     * 校验 zip 内所有 entry 名安全且可移植，并拒绝规范化后重名。任一不安全则抛
     * {@link PluginPackageException.Reason#UNSAFE}；不是合法 zip 抛 {@link PluginPackageException.Reason#MALFORMED}。
     * 只读，不写盘。
     */
    public static void assertSafeArchiveEntries(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                requireUniqueEntryName(entries.nextElement().getName(), names);
            }
        } catch (ZipException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "not a valid zip package: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "failed to read package for safety check: " + e.getMessage(), e);
        }
    }

    /**
     * 校验单个 entry 名安全：非空、非绝对路径（不以 {@code /} 开头、无盘符）、不含 {@code ..} 段，且相对一个虚拟根
     * 规范化后仍落在根内（纵深防御）。不安全抛 {@link PluginPackageException.Reason#UNSAFE}。
     */
    public static String requireSafeEntryName(String rawName) {
        return inspectEntryName(rawName).normalized();
    }

    /**
     * 校验 entry 名并把其可移植唯一键加入 {@code portableNames}；规范化后重名抛
     * {@link PluginPackageException.Reason#UNSAFE}。返回仅统一 {@code \\} / {@code /} 的实际归档名，供调用方读取与落盘。
     */
    public static String requireUniqueEntryName(String rawName, Set<String> portableNames) {
        Objects.requireNonNull(portableNames, "portableNames");
        SafeEntryName entryName = inspectEntryName(rawName);
        if (!portableNames.add(entryName.portableKey())) {
            throw new PluginPackageException(PluginPackageException.Reason.UNSAFE,
                    "duplicate zip entry after portable name normalization: " + rawName);
        }
        return entryName.normalized();
    }

    private static SafeEntryName inspectEntryName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw unsafe(rawName);
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/")) {
            throw unsafe(rawName);
        }
        if (WINDOWS_DRIVE.matcher(name).matches()) {
            throw unsafe(rawName);
        }
        String[] segments = name.split("/", -1);
        int segmentCount = segments.length;
        if (name.endsWith("/")) {
            segmentCount--;
        }
        if (segmentCount == 0) {
            throw unsafe(rawName);
        }
        StringBuilder portableKey = new StringBuilder(name.length());
        for (int i = 0; i < segmentCount; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                throw unsafe(rawName);
            }
            String portableSegment = Normalizer.normalize(segment, Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            portableSegment = Normalizer.normalize(portableSegment, Normalizer.Form.NFKC);
            if (portableSegment.isEmpty()
                    || portableSegment.equals(".")
                    || portableSegment.equals("..")
                    || portableSegment.endsWith(".")
                    || portableSegment.endsWith(" ")
                    || containsWindowsForbiddenCharacter(portableSegment)
                    || WINDOWS_RESERVED.matcher(portableSegment).matches()) {
                throw unsafe(rawName);
            }
            if (portableKey.length() > 0) {
                portableKey.append('/');
            }
            portableKey.append(portableSegment);
        }
        // 纵深防御：相对一个绝对虚拟根 resolve + normalize 后仍须在根内。
        Path base = Path.of(".").toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            throw unsafe(rawName);
        }
        return new SafeEntryName(name, portableKey.toString());
    }

    private static boolean containsWindowsForbiddenCharacter(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char character = segment.charAt(i);
            if (character < 32 || "<>:\"/\\|?*".indexOf(character) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 zip 内取出名为 {@code entryName} 的 entry 写入 {@code targetFile}。{@code targetFile} 是安装器规范生成的
     * 目标路径（不由 entry 名拼接），故无 Zip Slip；entry 名仍先做安全校验。
     */
    public static void extractEntryTo(Path zipFile, String entryName, Path targetFile) throws IOException {
        requireSafeEntryName(entryName);
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = null;
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry candidate = entries.nextElement();
                requireUniqueEntryName(candidate.getName(), names);
                if (candidate.getName().equals(entryName)) {
                    entry = candidate;
                }
            }
            if (entry == null) {
                throw new PluginPackageException(PluginPackageException.Reason.NO_DESCRIPTOR,
                        "entry not found while extracting: " + entryName);
            }
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static PluginPackageException unsafe(String rawName) {
        return new PluginPackageException(PluginPackageException.Reason.UNSAFE,
                "unsafe or non-portable zip entry name: " + rawName);
    }

    private record SafeEntryName(String normalized, String portableKey) {
    }
}
