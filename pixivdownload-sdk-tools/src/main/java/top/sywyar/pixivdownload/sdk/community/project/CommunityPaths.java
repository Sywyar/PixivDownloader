package top.sywyar.pixivdownload.sdk.community.project;

import top.sywyar.pixivdownload.plugin.runtime.install.verify.ZipSafety;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;

/** 社区相对路径复用安装器的可移植名称规则，同时检查实际目录与链接。 */
public final class CommunityPaths {
    public static final int MAX_PATH_UNITS = 1024;
    public static final int MAX_SEGMENTS = 64;
    private CommunityPaths() { }

    public static String relative(String value, boolean allowRoot) {
        if (allowRoot && ".".equals(value)) return value;
        if (value == null) throw invalid("");
        if (value.length() > MAX_PATH_UNITS) throw CommunityJson.limit(value, MAX_PATH_UNITS, "UTF-16");
        if (value.split("/", -1).length > MAX_SEGMENTS) throw CommunityJson.limit(value, MAX_SEGMENTS, "segments");
        try {
            if (value.endsWith("/") || !value.equals(ZipSafety.requireSafeEntryName(value))) throw invalid(value);
            return value;
        } catch (top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException | IllegalArgumentException e) {
            throw invalid(value);
        }
    }

    /** 检查实际目标，包括父目录；不存在的叶节点仍由最终 CREATE_NEW 操作报告文件系统错误。 */
    public static Path resolve(Path root, String value, boolean allowRoot, boolean mustExist) throws IOException {
        relative(value, allowRoot);
        Path absolute = root.toAbsolutePath().normalize();
        assertDirectoryChain(absolute);
        Path result = ".".equals(value) ? absolute : absolute.resolve(value);
        Path parent = absolute;
        if (!".".equals(value)) for (Path component : absolute.relativize(result)) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                try (var siblings = Files.newDirectoryStream(parent)) {
                    for (Path sibling : siblings) {
                        String actual = sibling.getFileName().toString();
                        if (actual.equals(component.toString())) continue;
                        var aliases = new HashSet<String>();
                        try {
                            ZipSafety.requireUniqueEntryName(actual, aliases);
                        } catch (RuntimeException ignored) {
                            // 与当前请求无关的旧名称不改变准入规则。
                            continue;
                        }
                        try { ZipSafety.requireUniqueEntryName(component.toString(), aliases); }
                        catch (RuntimeException e) { throw invalid(value); }
                    }
                }
            }
            parent = parent.resolve(component);
            try {
                BasicFileAttributes attrs = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther() || (!parent.equals(result) && !attrs.isDirectory())) throw invalid(value);
                if (!parent.toRealPath().startsWith(absolute.toRealPath())) throw invalid(value);
            } catch (NoSuchFileException e) {
                if (mustExist) throw e;
            }
        }
        return result;
    }

    private static void assertDirectoryChain(Path directory) throws IOException {
        Path current = directory.getRoot();
        for (Path component : directory) {
            current = current.resolve(component);
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw invalid(current.toString());
        }
    }

    private static ContractException invalid(String field) { return new ContractException("PATH_MISMATCH", field); }
}
