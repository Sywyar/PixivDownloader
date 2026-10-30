package top.sywyar.pixivdownload.plugin.api.contract.schema;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * SDK 远程值的固定编码，不调用自定义序列化器。字符串编码严格区分大小写并拒绝非规范表示。
 * 时间值采用 {@code 秒:九位纳秒}；Instant 的秒从 Unix epoch 起算，Duration 的秒允许为负。
 * 浮点采用大端 IEEE 754 位的定长小写十六进制，保留负零，只允许有限值。
 */
public enum ValueEncoding {
    /** 普通 JSON 值；常用 JDK 标量由生成器选择以下固定编码。 */
    JSON,
    /** long 的规范十进制字符串；需在 long 属性上显式选择。 */
    DECIMAL_STRING,
    /** UUID 的标准小写连字符字符串。 */
    UUID_STRING,
    /** Instant 的 epoch 秒和规范非负纳秒。 */
    INSTANT_SECONDS_NANOS,
    /** Duration 的有符号秒和规范非负纳秒。 */
    DURATION_SECONDS_NANOS,
    /** float 的八位十六进制 IEEE 754 编码。 */
    FLOAT32_HEX,
    /** double 的十六位十六进制 IEEE 754 编码。 */
    FLOAT64_HEX;

    /**
     * 把对应 JDK 标量转换为无损字符串；JSON 不使用此入口。
     * @param value 与编码对应的非空 JDK 标量
     * @return 规范字符串
     * @throws IllegalArgumentException 类型或值无效时返回稳定码，不附带原值
     */
    public String encode(Object value) {
        try {
            return switch (this) {
                case DECIMAL_STRING -> Long.toString((Long) value);
                case UUID_STRING -> ((UUID) value).toString();
                case INSTANT_SECONDS_NANOS -> time(((Instant) value).getEpochSecond(), ((Instant) value).getNano());
                case DURATION_SECONDS_NANOS -> time(((Duration) value).getSeconds(), ((Duration) value).getNano());
                case FLOAT32_HEX -> {
                    float number = (Float) value;
                    if (!Float.isFinite(number)) throw invalid();
                    yield java.util.HexFormat.of().toHexDigits(Float.floatToRawIntBits(number));
                }
                case FLOAT64_HEX -> {
                    double number = (Double) value;
                    if (!Double.isFinite(number)) throw invalid();
                    yield java.util.HexFormat.of().toHexDigits(Double.doubleToRawLongBits(number));
                }
                case JSON -> throw invalid();
            };
        } catch (RuntimeException failure) { throw invalid(); }
    }

    /**
     * 校验并恢复对应 JDK 标量；拒绝溢出、非有限浮点和非规范字符串。
     * @param text 编码字符串
     * @return 对应的 Long、UUID、Instant、Duration、Float 或 Double
     * @throws IllegalArgumentException 编码无效时返回稳定码，不附带原值
     */
    public Object decode(String text) {
        try {
            if (text == null || text.length() > 40) throw invalid();
            Object value = switch (this) {
                case DECIMAL_STRING -> Long.parseLong(text);
                case UUID_STRING -> UUID.fromString(text);
                case INSTANT_SECONDS_NANOS, DURATION_SECONDS_NANOS -> {
                    int colon = text.indexOf(':');
                    if (colon < 1 || text.length() - colon != 10) throw invalid();
                    long seconds = Long.parseLong(text.substring(0, colon));
                    int nanos = Integer.parseInt(text.substring(colon + 1));
                    if (nanos < 0 || nanos > 999_999_999) throw invalid();
                    yield this == INSTANT_SECONDS_NANOS ? Instant.ofEpochSecond(seconds, nanos) : Duration.ofSeconds(seconds, nanos);
                }
                case FLOAT32_HEX -> Float.intBitsToFloat(Integer.parseUnsignedInt(text, 16));
                case FLOAT64_HEX -> Double.longBitsToDouble(Long.parseUnsignedLong(text, 16));
                case JSON -> throw invalid();
            };
            if (!encode(value).equals(text)) throw invalid();
            return value;
        } catch (RuntimeException failure) { throw invalid(); }
    }

    private static String time(long seconds, int nanos) {
        String fraction = Integer.toString(nanos);
        return seconds + ":" + "0".repeat(9 - fraction.length()) + fraction;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("SDK_VALUE_ENCODING_INVALID"); }
}
