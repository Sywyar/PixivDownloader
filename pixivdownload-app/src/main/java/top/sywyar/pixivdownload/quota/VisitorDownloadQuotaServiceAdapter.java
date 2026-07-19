package top.sywyar.pixivdownload.quota;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;

import java.nio.file.Path;

/**
 * 将游客下载配额端口适配到宿主配额与归档服务。
 */
@Component
public class VisitorDownloadQuotaServiceAdapter implements VisitorDownloadQuotaService {

    private final UserQuotaService userQuotaService;

    public VisitorDownloadQuotaServiceAdapter(UserQuotaService userQuotaService) {
        this.userQuotaService = userQuotaService;
    }

    @Override
    public VisitorDownloadQuotaReservation checkAndReserve(String ownerUuid, int mediaCount) {
        UserQuotaService.QuotaCheckResult result = userQuotaService.checkAndReserve(ownerUuid, mediaCount);
        return new VisitorDownloadQuotaReservation(
                result.allowed(), result.artworksUsed(), result.maxArtworks(), result.resetSeconds());
    }

    @Override
    public String createArchive(String ownerUuid) {
        return userQuotaService.triggerArchive(ownerUuid);
    }

    @Override
    public void recordFolder(String ownerUuid, Path folder) {
        userQuotaService.recordFolder(ownerUuid, folder);
    }
}
