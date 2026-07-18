package top.sywyar.pixivdownload.core.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Unix 时间戳毫秒归一")
class EpochMillisNormalizerTest {

    @Test
    @DisplayName("常规秒级时间戳转毫秒且毫秒值保持不变")
    void convertsSecondsAndKeepsMilliseconds() {
        assertThat(EpochMillisNormalizer.normalize(1_700_000_000L)).isEqualTo(1_700_000_000_000L);
        assertThat(EpochMillisNormalizer.normalize(1_700_000_000_000L)).isEqualTo(1_700_000_000_000L);
    }

    @Test
    @DisplayName("阈值以下最后一个正值按秒换算且阈值本身按毫秒保留")
    void distinguishesValuesAtThreshold() {
        assertThat(EpochMillisNormalizer.normalize(999_999_999_999L))
                .isEqualTo(999_999_999_999_000L);
        assertThat(EpochMillisNormalizer.normalize(1_000_000_000_000L))
                .isEqualTo(1_000_000_000_000L);
    }

    @Test
    @DisplayName("非正数、空值和毫秒极值不被误换算")
    void keepsNonPositiveNullAndExtremeValues() {
        assertThat(EpochMillisNormalizer.normalize(0L)).isZero();
        assertThat(EpochMillisNormalizer.normalize(-1L)).isEqualTo(-1L);
        assertThat(EpochMillisNormalizer.normalize(Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE);
        assertThat(EpochMillisNormalizer.normalize(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(EpochMillisNormalizer.normalize((Long) null)).isZero();
    }
}
