package top.sywyar.pixivdownload.core.quota;

/**
 * 游客下载配额预留结果。
 */
public record VisitorDownloadQuotaReservation(
        boolean allowed,
        int quotaUnitsUsed,
        int maxQuotaUnits,
        long resetSeconds
) {
}
