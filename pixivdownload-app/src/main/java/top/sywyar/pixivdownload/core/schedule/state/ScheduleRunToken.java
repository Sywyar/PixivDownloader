package top.sywyar.pixivdownload.core.schedule.state;

/**
 * 一次原子认领或状态转换返回的运行 token。
 *
 * <p>调用方必须把本对象携带的 {@code claimToken + stateVersion} 原样带入下一次转换；不得在更新后
 * 另行读取版本来拼接一次运行。
 */
public record ScheduleRunToken(
        String claimToken,
        long stateVersion,
        ScheduleRunState runState
) {
    public ScheduleRunToken {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must not be negative");
        }
        if (runState == null) {
            throw new IllegalArgumentException("runState must not be null");
        }
    }
}
