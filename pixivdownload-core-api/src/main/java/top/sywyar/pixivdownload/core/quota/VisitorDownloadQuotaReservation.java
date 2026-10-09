package top.sywyar.pixivdownload.core.quota;

/**
 * 游客下载配额预留结果。
 *
 * @param allowed 此次下载配额是否已获准预留
 * @param quotaUnitsUsed 已使用的配额单位数
 * @param maxQuotaUnits 当前允许的配额单位上限
 * @param resetSeconds 距离配额重置的剩余秒数
 */
public record VisitorDownloadQuotaReservation(
        boolean allowed,
        int quotaUnitsUsed,
        int maxQuotaUnits,
        long resetSeconds
) {
}
