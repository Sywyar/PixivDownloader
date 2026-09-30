package top.sywyar.pixivdownload.core.metadata;

import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.LocalWorkAssetService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * 统一编排文件删除与数据库软删除。文件备份保留至记录事务完成；事务失败时尝试回滚文件。
 * 无法复原的副本由暂存区保留，不能把删除失败等同于已经完整恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreWorkDeletionService implements WorkDeletionService {

    private final WorkQueryService workQueryService;
    private final LocalWorkAssetService workAssetService;
    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;
    private final AppMessages messages;

    @Override
    public boolean delete(WorkType workType, long workId) {
        try (var workFileLease = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(workType, workId)) {
            if (!workQueryService.hasActiveWork(workType, workId)) {
                return false;
            }
            if (!workAssetService.deleteLocalFiles(workType, workId, () -> markDeleted(workType, workId))) {
                throw new WorkDeletionException(
                        WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED,
                        workType,
                        workId);
            }
            log.info(messages.getForLog("work.delete.log.deleted", typeNounForLog(workType), workId));
            return true;
        }
    }

    @Override
    public int deleteAll(WorkType workType, Collection<Long> workIds) {
        if (workIds == null || workIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Long id : new LinkedHashSet<>(workIds)) {
            if (id == null) continue;
            try {
                if (delete(workType, id)) deleted++;
            } catch (UnsafeDeletionPathException e) {
                throw e;
            } catch (Exception e) {
                log.warn(messages.getForLog("work.delete.log.delete-failed",
                        typeNounForLog(workType), id, deletionFailureForLog(e, workType, id)));
            }
        }
        return deleted;
    }

    /**
     * 清理派生 / 关联数据并标记软删除（主行保留）。仅供本类 {@link #delete} 在删文件成功后调用，
     * 不对插件暴露——避免「跳过删文件直接软删 DB」的乱序调用。
     */
    private void markDeleted(WorkType workType, long workId) {
        switch (workType) {
            case ARTWORK -> pixivDatabase.markArtworkDeleted(workId);
            case NOVEL -> novelMetadataRepository.markNovelDeleted(workId);
        }
    }

    /** 固定英文的日志作品类型名词。 */
    private String typeNounForLog(WorkType workType) {
        return messages.getForLog("work.type." + typeKey(workType));
    }

    private static String typeKey(WorkType workType) {
        return switch (workType) {
            case ARTWORK -> "artwork";
            case NOVEL -> "novel";
        };
    }

    private String deletionFailureForLog(Exception exception, WorkType workType, long workId) {
        if (exception instanceof WorkDeletionException deletionException
                && deletionException.reason() == WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED) {
            return messages.getForLog("work.delete.file-failed", typeNounForLog(workType), workId);
        }
        return exception.getMessage();
    }
}
