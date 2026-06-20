package top.sywyar.pixivdownload.core.metadata;

import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * {@link WorkDeletionService} 的核心实现：统一删除编排收敛在这里——判存
 * （{@link WorkQueryService#hasActiveWork}）→ 删磁盘文件（{@link WorkAssetService#deleteLocalFiles}）→
 * 文件全删成功后软删 DB 主行（插画代理 {@link PixivDatabase#markArtworkDeleted}、小说代理
 * {@link NovelMetadataRepository#markNovelDeleted}）。磁盘文件未能全部删除时抛 409、数据库不触碰，
 * 故 {@code delete()} 对调用方是「要么文件全删 + DB 软删、要么 DB 完全未变」。被注入的查询 / 资产服务
 * 实现均不反向依赖本服务，无 Bean 环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreWorkDeletionService implements WorkDeletionService {

    private final WorkQueryService workQueryService;
    private final WorkAssetService workAssetService;
    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;
    private final AppMessages messages;

    @Override
    public boolean delete(WorkType workType, long workId) {
        if (!workQueryService.hasActiveWork(workType, workId)) {
            return false;
        }
        if (!workAssetService.deleteLocalFiles(workType, workId)) {
            throw new LocalizedException(HttpStatus.CONFLICT,
                    "work.delete.file-failed",
                    "{0} {1} 的磁盘文件未能全部删除，已中止数据库清理",
                    typeNoun(workType), workId);
        }
        markDeleted(workType, workId);
        log.info(messages.getForLog("work.delete.log.deleted", typeNounForLog(workType), workId));
        return true;
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
            } catch (Exception e) {
                log.warn(messages.getForLog("work.delete.log.delete-failed",
                        typeNounForLog(workType), id, e.getMessage()));
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

    /** 作品类型名词（请求 locale，供面向用户的 409 文案）。 */
    private String typeNoun(WorkType workType) {
        return messages.get("work.type." + typeKey(workType));
    }

    /** 作品类型名词（JVM 系统 locale，供日志文案，与 {@code getForLog} 同 locale）。 */
    private String typeNounForLog(WorkType workType) {
        return messages.getForLog("work.type." + typeKey(workType));
    }

    private static String typeKey(WorkType workType) {
        return switch (workType) {
            case ARTWORK -> "artwork";
            case NOVEL -> "novel";
        };
    }
}
