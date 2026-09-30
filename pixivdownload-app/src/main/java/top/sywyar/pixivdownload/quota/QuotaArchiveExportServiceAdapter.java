package top.sywyar.pixivdownload.quota;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRules;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionService;

import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import java.nio.file.Path;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;

/**
 * 将核心归档导出端口适配到 quota owner 的既有 ZIP 任务实现。
 */
@Component
public class QuotaArchiveExportServiceAdapter implements ArchiveExportService {

    private final UserQuotaService userQuotaService;
    private final MultiModeSettings multiModeSettings;
    private final WorkDeletionService workDeletionService;
    private final PixivDatabase pixivDatabase;
    private final WorkAssetService workAssetService;
    private final NovelMetadataRepository novelMetadataRepository;

    public QuotaArchiveExportServiceAdapter(UserQuotaService userQuotaService,
                                            MultiModeSettings multiModeSettings,
                                            WorkDeletionService workDeletionService,
                                            PixivDatabase pixivDatabase,
                                            NovelMetadataRepository novelMetadataRepository,
                                            WorkAssetService workAssetService) {
        this.userQuotaService = userQuotaService;
        this.multiModeSettings = multiModeSettings;
        this.workDeletionService = workDeletionService;
        this.pixivDatabase = pixivDatabase;
        this.novelMetadataRepository = novelMetadataRepository;
        this.workAssetService = workAssetService;
    }

    @Override
    public String normalizeFormat(String format) {
        String normalized = ArchiveExportRules.normalizeFormatToken(format);
        if (!ArchiveExportRules.supportsFormat(normalized)) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "validation.archive.export.format.unsupported",
                    "不支持的打包格式：{0}", normalized);
        }
        return normalized;
    }

    @Override
    public ArchiveExportResult export(ArchiveExportRequest request) {
        Objects.requireNonNull(request, "request");
        normalizeFormat(request.format());
        if (request.entries().isEmpty() || request.fileCount() <= 0) {
            return ArchiveExportResult.empty(request.workCount());
        }

        WorkType deleteWorkType = resolveDeleteWorkType(request);
        List<Long> deleteWorkIds = request.deleteAfterReady() == null
                ? List.of() : request.deleteAfterReady().workIds();

        List<UserQuotaService.ArchiveItem> items = new ArrayList<>(request.entries().size());
        for (ArchiveExportEntry entry : request.entries()) {
            items.add(entry == null ? null : new UserQuotaService.ArchiveItem(
                    entry.sourcePath(), entry.entryName(), entry.bytes(), entry.workId()));
        }
        String token = userQuotaService.triggerAdminFileArchive(
                items, request.exportType(), request.workCount(), deleteWorkType == null
                        ? null
                        : prepareDeletion(deleteWorkType, deleteWorkIds, request.entries()),
                () -> deleteWorkType == null ? () -> {}
                        : top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquireAll(deleteWorkType, deleteWorkIds));
        long expireSeconds = (long) multiModeSettings.getArchiveExpireMinutes() * 60;
        return new ArchiveExportResult(token, expireSeconds, request.workCount(), request.fileCount());
    }

    /** 排队期间重下载或元数据变化时保留源作品，避免旧导出任务删除新登记。 */
    private Runnable prepareDeletion(WorkType type, List<Long> ids, List<ArchiveExportEntry> entries) {
        var expected = new LinkedHashMap<Long, Object>();
        Map<Long, Set<Path>> archivedFiles = entries.stream()
                .filter(entry -> entry != null && entry.workId() != null && entry.sourcePath() != null)
                .collect(Collectors.groupingBy(ArchiveExportEntry::workId, Collectors.mapping(
                        entry -> entry.sourcePath().toAbsolutePath().normalize(), Collectors.toSet())));
        for (Long id : ids) {
            if (id != null) expected.put(id, record(type, id));
        }
        return () -> {
            for (var work : expected.entrySet()) {
                if (work.getValue() == null || !work.getValue().equals(record(type, work.getKey()))) {
                    throw new IllegalStateException("Archive source work changed: " + work.getKey());
                }
            }
            for (Long id : expected.keySet()) {
                Set<Path> archived = archivedFiles.getOrDefault(id, Set.of());
                var asset = workAssetService.findAsset(type, id).orElseThrow();
                if (!workAssetService.hasCompleteFiles(type, id) || asset.files().isEmpty() || asset.files().size() < asset.pageCount()
                        || asset.files().stream().anyMatch(file -> !archived.contains(file.path().toAbsolutePath().normalize()))) {
                    throw new IllegalStateException("Archive does not contain all work files: " + id);
                }
            }
            for (Long id : expected.keySet()) {
                // 逐项传播失败，ZIP 仍由任务 owner 保留。
                workDeletionService.delete(type, id);
            }
        };
    }

    private Object record(WorkType type, long id) {
        return switch (type) {
            case ARTWORK -> pixivDatabase.getArtwork(id);
            case NOVEL -> novelMetadataRepository.getNovel(id);
        };
    }

    private static WorkType resolveDeleteWorkType(ArchiveExportRequest request) {
        if (request.deleteAfterReady() == null) {
            return null;
        }
        try {
            return WorkType.valueOf(request.deleteAfterReady().workType());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported archive deletion work type: "
                            + request.deleteAfterReady().workType(), e);
        }
    }
}
