package top.sywyar.pixivdownload.core.work.importing;

import top.sywyar.pixivdownload.core.work.model.WorkType;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/**
 * 仅登记已知的本地文件事实；未知元数据保持缺失，不能推断为非敏感或非 AI。
 * 文件顺序仅用于展示；来源页码与解析提示单独保留，不证明作品完整。
 * @param workType 作品类型
 * @param workId 正数作品 ID
 * @param sourceRoot 可信调用方批准的绝对源目录
 * @param pageFiles 从第零页开始连续排列的文件，最多 1000 个；小说只允许一个
 */
public record LocalWorkFileImportRequest(WorkType workType, long workId, Path sourceRoot, List<Path> pageFiles,
                                         List<Source> sources) {
    public LocalWorkFileImportRequest(WorkType type, long id, Path root, List<Path> files) {
        this(type, id, root, files, List.of());
    }
    /** 校验本地事实边界，磁盘和路径所有权由宿主再次核验。 */
    public LocalWorkFileImportRequest {
        Objects.requireNonNull(workType);
        Objects.requireNonNull(sourceRoot);
        pageFiles = List.copyOf(pageFiles);
        sources = List.copyOf(sources);
        if (workId <= 0 || !sourceRoot.isAbsolute() || pageFiles.isEmpty() || pageFiles.size() > 1000
                || workType == WorkType.NOVEL && pageFiles.size() != 1
                || !sources.isEmpty() && (sources.size() != pageFiles.size() || workType != WorkType.ARTWORK)
                || pageFiles.stream().distinct().count() != pageFiles.size()) {
            throw new IllegalArgumentException("INVALID_LOCAL_WORK_FILES");
        }
    }
    public Path root(int page) { return sources.isEmpty() ? sourceRoot : sources.get(page).root(); }
    public Path file(int page) { return root(page).resolve(pageFiles.get(page)).toAbsolutePath().normalize(); }
    public boolean parsed() { return !sources.isEmpty(); }

    /** 每个文件的批准根、明确来源页码和非权威解析提示。 */
    public record Source(Path root, Integer page, Map<String, String> hints) {
        public Source {
            Objects.requireNonNull(root);
            hints = Map.copyOf(hints);
            if (!root.isAbsolute() || page != null && page < 0 || hints.size() > 7
                    || hints.entrySet().stream().anyMatch(e -> e.getKey().length() > 32 || e.getValue().length() > 4096))
                throw new IllegalArgumentException("INVALID_LOCAL_WORK_FILES");
        }
    }
}
