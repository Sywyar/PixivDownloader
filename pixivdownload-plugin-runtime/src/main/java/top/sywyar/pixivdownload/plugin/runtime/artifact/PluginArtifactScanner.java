package top.sywyar.pixivdownload.plugin.runtime.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 外置插件安装根的一次性有界清点。这里只识别物理候选和文件系统类型；包结构、描述符与 provenance
 * 仍由各自的权威校验器处理。
 */
public final class PluginArtifactScanner {

    public static final int MAX_ROOT_ENTRIES = 2_048;
    public static final int MAX_CANDIDATES = 512;
    public static final long MAX_TOTAL_CANDIDATE_BYTES = 2L * 1024L * 1024L * 1024L;

    private PluginArtifactScanner() {
    }

    /**
     * 枚举安装根的全部直接 entry，并只返回 NOFOLLOW 意义下的普通 jar/zip 文件。
     * 任一候选是链接、特殊文件或累计预算超限时拒绝整次扫描，避免部分清点被误当成完整事实。
     */
    public static ScanResult scan(Path directory) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        BasicFileAttributes rootAttributes = attributesIfPresent(root);
        if (rootAttributes == null) {
            return new ScanResult(root, false, 0, 0L, List.of());
        }
        if (rootAttributes.isSymbolicLink() || rootAttributes.isOther() || !rootAttributes.isDirectory()) {
            throw new IOException("plugins root must be a plain directory: " + root);
        }

        List<Path> candidates = new ArrayList<>();
        int entryCount = 0;
        long candidateBytes = 0L;
        try (Stream<Path> entries = Files.list(root)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path candidate = iterator.next().toAbsolutePath().normalize();
                if (!root.equals(candidate.getParent())) {
                    throw new IOException("plugins root yielded a non-direct entry: " + candidate);
                }
                if (++entryCount > MAX_ROOT_ENTRIES) {
                    throw new IOException("plugins root exceeds the supported entry count");
                }
                if (!hasCandidateName(candidate)) {
                    continue;
                }
                if (candidates.size() >= MAX_CANDIDATES) {
                    throw new IOException("plugins root exceeds the supported artifact count");
                }
                BasicFileAttributes attributes = attributesIfPresent(candidate);
                if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isRegularFile()) {
                    throw new IOException("visible plugin artifact must be a plain regular file: " + candidate);
                }
                if (attributes.size() > MAX_TOTAL_CANDIDATE_BYTES - candidateBytes) {
                    throw new IOException("plugins root artifacts exceed the cumulative byte budget");
                }
                candidateBytes += attributes.size();
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return new ScanResult(root, true, entryCount, candidateBytes, candidates);
    }

    public static boolean hasCandidateName(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && (name.endsWith(".jar") || name.endsWith(".zip"));
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    public record ScanResult(Path root, boolean rootPresent, int entryCount,
                             long candidateBytes, List<Path> candidates) {

        public ScanResult {
            root = Objects.requireNonNull(root, "root");
            if (entryCount < 0 || candidateBytes < 0L) {
                throw new IllegalArgumentException("scan counters must not be negative");
            }
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }
}
