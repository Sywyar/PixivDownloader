package top.sywyar.pixivdownload.gallery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.GalleryQuery;
import top.sywyar.pixivdownload.core.metadata.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.download.response.DownloadedResponse;
import top.sywyar.pixivdownload.download.response.PagedHistoryResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.PagedResult;
import top.sywyar.pixivdownload.plugin.api.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.SeriesNeighbors;
import top.sywyar.pixivdownload.plugin.api.TagOption;
import top.sywyar.pixivdownload.plugin.api.TagQuery;
import top.sywyar.pixivdownload.plugin.api.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.WorkTag;
import top.sywyar.pixivdownload.plugin.api.WorkType;

import java.util.*;

/**
 * 画廊页面服务：列表 / 详情 / 标签 / 关联查询统一走核心接口——{@link WorkQueryService}
 * 取 id 分页与目录聚合、{@link WorkMetadataRepository} 批量补全行数据（search → hydrate 两步）；
 * 删除链路同样走核心接口——{@link WorkAssetService} 删文件、{@link WorkDeletionService}
 * 标记软删除，不再直接依赖底层数据库与下载侧文件定位。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class GalleryService {

    private final WorkQueryService workQueryService;
    private final WorkMetadataRepository workMetadataRepository;
    private final WorkAssetService workAssetService;
    private final WorkDeletionService workDeletionService;
    private final AppMessages messages;

    public PagedHistoryResponse query(GalleryQuery query) {
        PagedResult<WorkSummary> result = workQueryService.search(toWorkQuery(query));
        List<DownloadedResponse> content = toResponses(toIds(result.content()));
        return new PagedHistoryResponse(content, result.totalElements(),
                result.page(), result.size(), result.totalPages());
    }

    public List<Long> findArtworkIds(GalleryQuery query) {
        return toIds(workQueryService.searchAll(toWorkQuery(query)));
    }

    public List<GalleryRepository.TagOption> listTags(String search, int limit, GuestRestriction restriction) {
        List<TagOption> tags = workQueryService.tags(
                new TagQuery(WorkType.ARTWORK, search, limit, toWorkRestriction(restriction)));
        List<GalleryRepository.TagOption> out = new ArrayList<>(tags.size());
        for (TagOption tag : tags) {
            out.add(toRepositoryTag(tag));
        }
        return out;
    }

    public List<GalleryRepository.TagOption> listTags(String search, int limit) {
        return listTags(search, limit, null);
    }

    public GalleryRepository.TagOption findTag(String name, String translatedName) {
        return workQueryService.tagByName(WorkType.ARTWORK, name, translatedName)
                .map(GalleryService::toRepositoryTag)
                .orElse(null);
    }

    public DownloadedResponse findArtwork(long artworkId) {
        return workMetadataRepository.find(WorkType.ARTWORK, artworkId)
                .map(GalleryService::toDownloadedResponse)
                .orElse(null);
    }

    public List<DownloadedResponse> related(long artworkId, int limit) {
        return toResponses(toIds(
                workQueryService.relatedByTags(WorkType.ARTWORK, artworkId, clampLimit(limit))));
    }

    public List<DownloadedResponse> byAuthor(long artworkId, int limit) {
        WorkMetadata base = workMetadataRepository.find(WorkType.ARTWORK, artworkId).orElse(null);
        if (base == null || base.authorId() == null) {
            return List.of();
        }
        return toResponses(toIds(workQueryService.byAuthor(
                WorkType.ARTWORK, base.authorId(), artworkId, clampLimit(limit))));
    }

    public List<DownloadedResponse> bySeries(long artworkId, int limit) {
        WorkMetadata base = workMetadataRepository.find(WorkType.ARTWORK, artworkId).orElse(null);
        if (base == null || base.seriesId() == null || base.seriesId() <= 0) {
            return List.of();
        }
        return toResponses(toIds(workQueryService.bySeries(
                WorkType.ARTWORK, base.seriesId(), artworkId, clampLimit(limit))));
    }

    /** 系列内相邻作品导航；作品不存在、无系列或无序号时返回 {@code null}。 */
    public SeriesNeighbors seriesNeighbors(long artworkId) {
        return workQueryService.seriesNeighbors(WorkType.ARTWORK, artworkId).orElse(null);
    }

    /**
     * 删除单个作品：先删磁盘文件（图片 / 缩略图 / 图库缓存 / 空目录，经
     * {@link WorkAssetService#deleteLocalFiles}），再清理 DB 派生数据并标记软删除
     * （感知哈希 / 标签关联 / 收藏夹关联照旧清理，主行保留并置 {@code deleted = 1}，经
     * {@link WorkDeletionService#markDeleted}）——使下载判重能识别「已下载过，但被删除」、
     * 避免被当作未下载重新下载。作品不存在或已被标记删除时返回 {@code false}。磁盘文件删除失败
     * （被锁定 / 权限不足等）会立即抛出，不再继续动 DB，避免 DB 与磁盘状态不一致出现孤儿文件。
     */
    public boolean deleteArtwork(long artworkId) {
        if (!workQueryService.hasActiveWork(WorkType.ARTWORK, artworkId)) {
            return false;
        }
        if (!workAssetService.deleteLocalFiles(WorkType.ARTWORK, artworkId)) {
            throw new LocalizedException(HttpStatus.CONFLICT,
                    "gallery.delete.file-failed",
                    "作品 {0} 的磁盘文件未能全部删除，已中止数据库清理",
                    artworkId);
        }
        workDeletionService.markDeleted(WorkType.ARTWORK, artworkId);
        log.info(logMessage("gallery.log.deleted", artworkId));
        return true;
    }

    /** 批量删除作品，返回实际删除的数量。 */
    public int deleteArtworks(Collection<Long> artworkIds) {
        if (artworkIds == null || artworkIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Long id : new LinkedHashSet<>(artworkIds)) {
            if (id == null) continue;
            try {
                if (deleteArtwork(id)) deleted++;
            } catch (Exception e) {
                log.warn(logMessage("gallery.log.delete-failed", id, e.getMessage()));
            }
        }
        return deleted;
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 12;
        return Math.min(limit, 60);
    }

    private List<DownloadedResponse> toResponses(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<WorkMetadata> metas = workMetadataRepository.findAll(WorkType.ARTWORK, ids);
        List<DownloadedResponse> out = new ArrayList<>(metas.size());
        for (WorkMetadata meta : metas) {
            out.add(toDownloadedResponse(meta));
        }
        return out;
    }

    private static List<Long> toIds(List<WorkSummary> summaries) {
        List<Long> out = new ArrayList<>(summaries.size());
        for (WorkSummary summary : summaries) {
            out.add(summary.workId());
        }
        return out;
    }

    private WorkQuery toWorkQuery(GalleryQuery query) {
        return WorkQuery.builder(WorkType.ARTWORK)
                .page(query.getPage())
                .size(query.getSize())
                .sort(query.getSort())
                .order(query.getOrder())
                .search(query.getSearch())
                .searchType(query.getSearchType())
                .r18(query.getR18())
                .ai(query.getAi())
                .formats(query.getFormats())
                .collectionIds(query.getCollectionIds())
                .tagIds(query.getTagIds())
                .excludedTagIds(query.getExcludedTagIds())
                .optionalTagIds(query.getOptionalTagIds())
                .authorIds(query.getAuthorIds())
                .excludedAuthorIds(query.getExcludedAuthorIds())
                .optionalAuthorIds(query.getOptionalAuthorIds())
                .seriesIds(query.getSeriesIds())
                .excludedSeriesIds(query.getExcludedSeriesIds())
                .restriction(toWorkRestriction(query.getGuestRestriction()))
                .build();
    }

    private static WorkRestriction toWorkRestriction(GuestRestriction restriction) {
        if (restriction == null) {
            return null;
        }
        return new WorkRestriction(
                restriction.allowedXRestricts(),
                restriction.tagUnrestricted(),
                restriction.tagIds(),
                restriction.authorUnrestricted(),
                restriction.authorIds());
    }

    private static GalleryRepository.TagOption toRepositoryTag(TagOption tag) {
        return new GalleryRepository.TagOption(
                tag.tagId(), tag.name(), tag.translatedName(), (int) tag.workCount());
    }

    private static DownloadedResponse toDownloadedResponse(WorkMetadata meta) {
        Long seriesId = meta.seriesId();
        return DownloadedResponse.builder()
                .artworkId(meta.workId())
                .title(meta.title())
                .folder(meta.folder())
                .count(meta.pageCount())
                .extensions(meta.extensions())
                .time(meta.downloadTime())
                .moved(meta.moved())
                .moveFolder(meta.moveFolder())
                .moveTime(meta.moveTime())
                .xRestrict(meta.xRestrict())
                .isAi(meta.isAi())
                .authorId(meta.authorId())
                .authorName(meta.authorName())
                .description(meta.description())
                .fileName(meta.fileNameTemplateId())
                .fileNameTemplate(meta.fileNameTemplate())
                .tags(toTagDtos(meta.tags()))
                .seriesId(seriesId == null || seriesId <= 0 ? null : seriesId)
                .seriesOrder(meta.seriesOrder())
                .seriesTitle(meta.seriesTitle())
                .deleted(false)
                .build();
    }

    private static List<TagDto> toTagDtos(List<WorkTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<TagDto> out = new ArrayList<>(tags.size());
        for (WorkTag tag : tags) {
            out.add(new TagDto(tag.tagId(), tag.name(), tag.translatedName()));
        }
        return out;
    }
}
