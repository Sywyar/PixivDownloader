package top.sywyar.pixivdownload.core.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.plugin.api.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.WorkType;

/**
 * {@link WorkDeletionService} 的核心实现：插画侧代理 {@link PixivDatabase#markArtworkDeleted}，
 * 小说侧代理 {@link NovelMetadataRepository#markNovelDeleted}，软删除语义（派生 / 关联数据清理 +
 * 主行置 {@code deleted = 1}）与直接调用两者完全一致。两侧仓库均为核心数据层 Bean。
 */
@Component
@RequiredArgsConstructor
public class CoreWorkDeletionService implements WorkDeletionService {

    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;

    @Override
    public void markDeleted(WorkType workType, long workId) {
        switch (workType) {
            case ARTWORK -> pixivDatabase.markArtworkDeleted(workId);
            case NOVEL -> novelMetadataRepository.markNovelDeleted(workId);
        }
    }
}
