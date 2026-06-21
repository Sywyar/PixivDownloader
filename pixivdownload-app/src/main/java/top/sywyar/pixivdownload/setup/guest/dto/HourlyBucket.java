package top.sywyar.pixivdownload.setup.guest.dto;

/**
 * 单个小时桶的访问统计。
 *
 * @param hourEpochMillis 桶起始时刻的毫秒时间戳（{@code bucketHour * 3_600_000L}）
 * @param count           桶内累计请求数
 */
public record HourlyBucket(long hourEpochMillis, long count) {
}
