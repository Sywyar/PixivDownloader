package top.sywyar.pixivdownload.core.time;

/**
 * 按既有阈值约定，将可能以秒或毫秒表示的 Unix 时间戳归一为毫秒。
 * 仅正数且小于 {@code 1_000_000_000_000} 时按秒值乘以 {@code 1000}；
 * 阈值本身、非正数和更大值均原样返回。
 */
public final class EpochMillisNormalizer {

    private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;

    private EpochMillisNormalizer() {
    }

    /**
     * 归一 primitive 时间戳，保留非正数作为调用方可继续解释的无效值。
     */
    public static long normalize(long timestamp) {
        if (timestamp > 0 && timestamp < EPOCH_MILLIS_THRESHOLD) {
            return timestamp * 1000L;
        }
        return timestamp;
    }

    /**
     * 归一 boxed 时间戳；{@code null} 按旧契约映射为 {@code 0}。
     */
    public static long normalize(Long timestamp) {
        return timestamp == null ? 0L : normalize(timestamp.longValue());
    }
}
