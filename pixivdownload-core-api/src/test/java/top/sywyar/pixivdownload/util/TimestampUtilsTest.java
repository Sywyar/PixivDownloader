package top.sywyar.pixivdownload.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("时间戳归一工具")
class TimestampUtilsTest {

    @Test
    @DisplayName("秒级时间戳转毫秒且毫秒值保持不变")
    void convertsSecondsAndKeepsMilliseconds() {
        assertThat(TimestampUtils.toMillis(1_700_000_000L)).isEqualTo(1_700_000_000_000L);
        assertThat(TimestampUtils.toMillis(1_700_000_000_000L)).isEqualTo(1_700_000_000_000L);
        assertThat(TimestampUtils.toMillis(0L)).isZero();
        assertThat(TimestampUtils.toMillis((Long) null)).isZero();
    }
}
