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
     * 枚举安装根的全部直接 entry，并只返回 NOFOLLOW 意义下的普通 jar/zip 文件。单个候选是链接、特殊文件或
     * 超出累计预算时记录为独立拒绝项；安装根本身或全局枚举预算异常仍拒绝整次扫描。
     */
    public static ScanResult scan(Path directory) throws IOException {
        Path root = PluginRuntimeLayout.resolveExistingPluginsRoot(
                Objects.requireNonNull(directory, "directory"));
        BasicFileAttributes rootAttributes = attributesIfPresent(root);
        if (rootAttributes == null) {
            return new ScanResult(root, false, 0, 0L, List.of(), List.of());
        }
        if (rootAttributes.isSymbolicLink() || rootAttributes.isOther() || !rootAttributes.isDirectory()) {
            throw new IOException("plugins root must be a plain directory: " + root);
        }

        List<Path> candidates = new ArrayList<>();
        List<RejectedCandidate> rejectedCandidates = new ArrayList<>();
        int entryCount = 0;
        int namedCandidateCount = 0;
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
                if (++namedCandidateCount > MAX_CANDIDATES) {
                    throw new IOException("plugins root exceeds the supported artifact count");
                }
                BasicFileAttributes attributes;
                try {
                    attributes = attributesIfPresent(candidate);
                } catch (IOException failure) {
                    rejectedCandidates.add(new RejectedCandidate(candidate,
                            "visible plugin artifact could not be inspected: " + failure.getMessage()));
                    continue;
                }
                if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isRegularFile()) {
                    rejectedCandidates.add(new RejectedCandidate(candidate,
                            "visible plugin artifact must be a plain regular file: " + candidate));
                    continue;
                }
                if (attributes.size() > MAX_TOTAL_CANDIDATE_BYTES - candidateBytes) {
                    rejectedCandidates.add(new RejectedCandidate(candidate,
                            "plugin artifact exceeds the remaining cumulative byte budget: " + candidate));
                    continue;
                }
                candidateBytes += attributes.size();
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
        rejectedCandidates.sort(Comparator.comparing(rejected -> rejected.path().getFileName().toString()));
        return new ScanResult(root, true, entryCount, candidateBytes, candidates, rejectedCandidates);
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
                             long candidateBytes, List<Path> candidates,
                             List<RejectedCandidate> rejectedCandidates) {

        public ScanResult {
            root = Objects.requireNonNull(root, "root");
            if (entryCount < 0 || candidateBytes < 0L) {
                throw new IllegalArgumentException("scan counters must not be negative");
            }
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            rejectedCandidates = List.copyOf(
                    Objects.requireNonNull(rejectedCandidates, "rejectedCandidates"));
        }
    }

    public record RejectedCandidate(Path path, String reason) {

        public RejectedCandidate {
            path = Objects.requireNonNull(path, "path");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }
}
