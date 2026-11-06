package top.sywyar.pixivdownload.core.work.importing;

import top.sywyar.pixivdownload.core.work.model.WorkType;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 仅登记已知的本地文件事实；未知元数据保持缺失，不能推断为非敏感或非 AI。
 * 页数只代表本地连续页集合，不保证与来源站点当前页数一致。
 * @param workType 作品类型
 * @param workId 正数作品 ID
 * @param sourceRoot 可信调用方批准的绝对源目录
 * @param pageFiles 从第零页开始连续排列的文件，最多 1000 个；小说只允许一个
 */
public record LocalWorkFileImportRequest(WorkType workType, long workId, Path sourceRoot, List<Path> pageFiles) {
    /** 校验本地事实边界，磁盘和路径所有权由宿主再次核验。 */
    public LocalWorkFileImportRequest {
        Objects.requireNonNull(workType);
        Objects.requireNonNull(sourceRoot);
        pageFiles = List.copyOf(pageFiles);
        if (workId <= 0 || !sourceRoot.isAbsolute() || pageFiles.isEmpty() || pageFiles.size() > 1000
                || workType == WorkType.NOVEL && pageFiles.size() != 1
                || pageFiles.stream().distinct().count() != pageFiles.size()) {
            throw new IllegalArgumentException("INVALID_LOCAL_WORK_FILES");
        }
    }
}
