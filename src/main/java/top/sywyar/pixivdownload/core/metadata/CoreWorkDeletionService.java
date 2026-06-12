package top.sywyar.pixivdownload.core.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.plugin.api.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.WorkType;

/**
 * {@link WorkDeletionService} 的核心实现：插画侧代理 {@link PixivDatabase#markArtworkDeleted}，
 * 小说侧代理 {@link NovelDatabase#markNovelDeleted}，软删除语义（派生 / 关联数据清理 +
 * 主行置 {@code deleted = 1}）与直接调用两者完全一致。过渡期本类对 novel.db 包的 import
 * 待小说侧仓库收编进核心数据层后消除。
 */
@Component
@RequiredArgsConstructor
public class CoreWorkDeletionService implements WorkDeletionService {

    private final PixivDatabase pixivDatabase;
    private final NovelDatabase novelDatabase;

    @Override
    public void markDeleted(WorkType workType, long workId) {
        switch (workType) {
            case ARTWORK -> pixivDatabase.markArtworkDeleted(workId);
            case NOVEL -> novelDatabase.markNovelDeleted(workId);
        }
    }
}
