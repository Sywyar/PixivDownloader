package top.sywyar.pixivdownload.plugin.runtime.install;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Zip Slip 防护工具：校验 zip entry 名安全（解压后不逃逸出目标目录），并提供安全的单 entry 解压。
 *
 * <p>安装器对会被 runtime 后续物化的 {@code .zip} 包先做 {@link #assertNoTraversal(Path)} 全量校验：任一 entry 名
 * 为绝对路径、含盘符、或含 {@code ..} 段都拒绝整包。这样既挡住安装阶段的越界写，也保证落盘后的 zip 被物化到
 * {@code plugins/runtime/} 时不会越界。
 *
 * <p>安装器自身<b>从不</b>用不可信 entry 名拼接磁盘路径——单 jar 形态解压时目标文件名是安装器规范生成的
 * （{@code {id}-{version}.jar}），entry 名仅用于在 zip 内定位源；解压目录形态则原样复制已校验过的整 zip。因此越界写
 * 在安装阶段被彻底排除。
 */
public final class ZipSafety {

    /** 盘符根（如 {@code C:}、{@code C:/x}、{@code C:x}）——分隔符已统一为 {@code /} 后匹配。 */
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("(?s)^[A-Za-z]:.*");

    private ZipSafety() {
    }

    /**
     * 校验 zip 内所有 entry 名安全（无 Zip Slip）。任一不安全则抛
     * {@link PluginPackageException.Reason#UNSAFE}；不是合法 zip 抛 {@link PluginPackageException.Reason#MALFORMED}。
     * 只读，不写盘。
     */
    public static void assertNoTraversal(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                requireSafeEntryName(entries.nextElement().getName());
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
    public static void requireSafeEntryName(String rawName) {
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
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..")) {
                throw unsafe(rawName);
            }
        }
        // 纵深防御：相对一个绝对虚拟根 resolve + normalize 后仍须在根内。
        Path base = Path.of(".").toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            throw unsafe(rawName);
        }
    }

    /**
     * 从 zip 内取出名为 {@code entryName} 的 entry 写入 {@code targetFile}。{@code targetFile} 是安装器规范生成的
     * 目标路径（不由 entry 名拼接），故无 Zip Slip；entry 名仍先做安全校验。
     */
    public static void extractEntryTo(Path zipFile, String entryName, Path targetFile) throws IOException {
        requireSafeEntryName(entryName);
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
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
                "unsafe zip entry (path traversal / absolute path rejected): " + rawName);
    }
}
