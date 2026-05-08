package top.sywyar.pixivdownload.util;

public final class TimestampUtils {

    public static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;

    private TimestampUtils() {
    }

    public static long nowMillis() {
        return System.currentTimeMillis();
    }

    public static long toMillis(long timestamp) {
        if (timestamp > 0 && timestamp < EPOCH_MILLIS_THRESHOLD) {
            return timestamp * 1000L;
        }
        return timestamp;
    }

    public static long toMillis(Long timestamp) {
        return timestamp == null ? 0L : toMillis(timestamp.longValue());
    }
}
