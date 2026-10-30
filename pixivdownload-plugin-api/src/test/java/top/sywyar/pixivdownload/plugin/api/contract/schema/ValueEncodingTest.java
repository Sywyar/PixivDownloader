package top.sywyar.pixivdownload.plugin.api.contract.schema;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("固定字符串编码保留 JDK 标量的全部信息")
class ValueEncodingTest {
    @Test @DisplayName("极值、纳秒、负零与 UUID 无损往返")
    void roundTrip() {
        Map<ValueEncoding, List<?>> values = Map.of(
                ValueEncoding.DECIMAL_STRING, List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L),
                ValueEncoding.UUID_STRING, List.of(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")),
                ValueEncoding.INSTANT_SECONDS_NANOS, List.of(Instant.MIN, Instant.MAX, Instant.ofEpochSecond(-1, 1)),
                ValueEncoding.DURATION_SECONDS_NANOS, List.of(Duration.ofSeconds(Long.MIN_VALUE), Duration.ofSeconds(Long.MAX_VALUE, 999999999)),
                ValueEncoding.FLOAT32_HEX, List.of(Float.MIN_VALUE, Float.MAX_VALUE, -0.0f),
                ValueEncoding.FLOAT64_HEX, List.of(Double.MIN_VALUE, Double.MAX_VALUE, -0.0));
        values.forEach((encoding, cases) -> cases.forEach(value -> assertThat(encoding.decode(encoding.encode(value))).isEqualTo(value)));
        assertThat(ValueEncoding.FLOAT32_HEX.encode(-0.0f)).isEqualTo("80000000");
        assertThat(ValueEncoding.FLOAT64_HEX.encode(Double.MIN_VALUE)).isEqualTo("0000000000000001");
        assertThat(ValueEncoding.INSTANT_SECONDS_NANOS.encode(Instant.MIN)).isEqualTo("-31557014167219200:000000000");
        assertThat(ValueEncoding.INSTANT_SECONDS_NANOS.encode(Instant.MAX)).isEqualTo("31556889864403199:999999999");
    }

    @Test @DisplayName("溢出、非有限浮点与非规范表示以不含原值的稳定码拒绝")
    void rejectsInvalid() {
        Map<ValueEncoding, List<String>> values = Map.of(
                ValueEncoding.DECIMAL_STRING, List.of("+1", "01", "-0", "9223372036854775808"),
                ValueEncoding.UUID_STRING, List.of("1-1-1-1-1", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"),
                ValueEncoding.INSTANT_SECONDS_NANOS, List.of("31556889864403200:000000000", "0:1"),
                ValueEncoding.DURATION_SECONDS_NANOS, List.of("0:1000000000", "0:-00000001"),
                ValueEncoding.FLOAT32_HEX, List.of("7f800000", "7fc00000", "0", "FF7FFFFF"),
                ValueEncoding.FLOAT64_HEX, List.of("fff0000000000000", "7ff8000000000000"));
        values.forEach((encoding, cases) -> cases.forEach(value -> assertThatThrownBy(() -> encoding.decode(value))
                .hasMessage("SDK_VALUE_ENCODING_INVALID").hasNoCause()));
        assertThatThrownBy(() -> ValueEncoding.FLOAT64_HEX.encode(Double.NaN)).hasMessage("SDK_VALUE_ENCODING_INVALID").hasNoCause();
    }
}
