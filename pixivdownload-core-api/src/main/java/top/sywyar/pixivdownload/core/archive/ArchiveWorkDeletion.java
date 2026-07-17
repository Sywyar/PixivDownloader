package top.sywyar.pixivdownload.core.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 归档就绪后由宿主统一删除作品的纯值指令。
 *
 * <p>{@code workType} 使用调用方稳定作品类型的机器 token；宿主适配器必须在创建后台任务前
 * 完成解析并对未知 token fail-closed，不能把插件回调对象交给异步归档队列。
 */
public record ArchiveWorkDeletion(String workType, List<Long> workIds) {

    public ArchiveWorkDeletion {
        if (workType == null || workType.isBlank()) {
            throw new IllegalArgumentException("workType must not be blank");
        }
        workType = workType.trim();
        workIds = workIds == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(workIds));
    }
}
