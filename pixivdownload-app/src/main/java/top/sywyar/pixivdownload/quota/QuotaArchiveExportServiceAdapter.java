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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将核心归档导出端口适配到 quota owner 的既有 ZIP 任务实现。
 */
@Component
public class QuotaArchiveExportServiceAdapter implements ArchiveExportService {

    private final UserQuotaService userQuotaService;
    private final MultiModeSettings multiModeSettings;
    private final WorkDeletionService workDeletionService;

    public QuotaArchiveExportServiceAdapter(UserQuotaService userQuotaService,
                                            MultiModeSettings multiModeSettings,
                                            WorkDeletionService workDeletionService) {
        this.userQuotaService = userQuotaService;
        this.multiModeSettings = multiModeSettings;
        this.workDeletionService = workDeletionService;
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
                        : () -> workDeletionService.deleteAll(
                                deleteWorkType, deleteWorkIds));
        long expireSeconds = (long) multiModeSettings.getArchiveExpireMinutes() * 60;
        return new ArchiveExportResult(token, expireSeconds, request.workCount(), request.fileCount());
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
