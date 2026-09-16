package top.sywyar.pixivdownload.core.work;

import top.sywyar.pixivdownload.core.work.service.DownloadPathLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 查询已有父目录所在的文件系统；无法确认的限制交给实际文件操作判定。 */
final class FileSystemPathLimits {
    private FileSystemPathLimits() {}

    static DownloadPathLimits read(Path directory) {
        Path existing = directory.toAbsolutePath().normalize();
        while (existing != null && !Files.isDirectory(existing)) existing = existing.getParent();
        if (existing == null) return DownloadPathLimits.UNKNOWN;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            try {
                String type = Files.getFileStore(existing).type().toUpperCase(Locale.ROOT);
                int component = Set.of("NTFS", "REFS", "FAT", "FAT32", "EXFAT").contains(type) ? 255 : 0;
                // JDK NIO 使用扩展路径，不把 Win32 的旧 260 字符限制当成磁盘能力。
                return new DownloadPathLimits(component, 32001, false);
            } catch (IOException unavailable) {
                return DownloadPathLimits.UNKNOWN;
            }
        }
        if (os.contains("linux") || os.contains("mac") || os.contains("darwin")) {
            // Apple 卷的 NAME_MAX 不一定以 UTF-8 字节计数，组成部分由实际卷只读探测。
            int component = os.contains("linux") ? query("NAME_MAX", existing) : 0;
            return new DownloadPathLimits(component, query("PATH_MAX", existing), true);
        }
        return DownloadPathLimits.UNKNOWN;
    }

    static java.util.function.Predicate<Path> support(Path directory) {
        DownloadPathLimits limits = read(directory);
        Path parent = directory.toAbsolutePath().normalize();
        while (parent != null && !Files.isDirectory(parent)) parent = parent.getParent();
        if (parent == null) return limits::accepts;
        Path existing = parent;
        // 使用同一卷自身返回的原因识别超长，避免依赖操作系统语言或误判权限、磁盘错误。
        String tooLong = fileSystemReason(existing.resolve("x".repeat(1024)));
        if (tooLong == null || fileSystemReason(existing.resolve("path-capability-probe")) != null) {
            return limits::accepts;
        }
        java.util.Map<String, Boolean> components = new java.util.HashMap<>();
        return candidate -> {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (!limits.accepts(absolute) || tooLong.equals(fileSystemReason(absolute))) return false;
            Path relative = absolute.startsWith(existing) ? existing.relativize(absolute) : absolute;
            for (Path part : relative) {
                if (!components.computeIfAbsent(part.toString(),
                        value -> !tooLong.equals(fileSystemReason(existing.resolve(value))))) return false;
            }
            return true;
        };
    }

    private static String fileSystemReason(Path path) {
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException ignored) {
            return null;
        } catch (java.nio.file.FileSystemException failure) {
            return failure.getReason();
        } catch (IOException | SecurityException ignored) {
            return null;
        }
        return null;
    }

    private static int query(String key, Path directory) {
        Process process = null;
        try {
            process = new ProcessBuilder("getconf", key, directory.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return 0;
            byte[] output = process.getInputStream().readNBytes(32);
            if (output.length == 32) return 0;
            int value = Integer.parseInt(new String(output, StandardCharsets.UTF_8).trim());
            return Math.max(0, value);
        } catch (IOException | NumberFormatException unavailable) {
            return 0;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
