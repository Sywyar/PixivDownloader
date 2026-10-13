package top.sywyar.pixivdownload.plugin.api.database.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 格式 1 的无损值校验和类型化摘要；不认识数据库产品或 JDBC 类型。 */
public final class MigrationValues {
    private MigrationValues() {}
    /**
     * 将可无损表示的值规范为中性类型；拒绝溢出、非有限浮点数和非法文本。
     * @param value 原始值或空值
     * @param type 中性列类型
     * @return 空值、Long、Double、String 或 byte[]
     */
    public static Object canonical(Object value, String type) {
        if (value == null) return null;
        return switch (type) {
            case "INTEGER" -> {
                if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
                    throw invalid("INTEGER_VALUE");
                yield ((Number) value).longValue();
            }
            case "REAL" -> {
                if (!(value instanceof Double || value instanceof Float || value instanceof Byte
                        || value instanceof Short || value instanceof Integer || value instanceof Long)) throw invalid("REAL_VALUE");
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number)) throw invalid("REAL_VALUE");
                if (value instanceof Long integer && java.math.BigDecimal.valueOf(integer)
                        .compareTo(new java.math.BigDecimal(number)) != 0) throw invalid("REAL_PRECISION");
                yield number;
            }
            case "TEXT" -> {
                if (!(value instanceof String text)) throw invalid("TEXT_VALUE");
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (Character.isHighSurrogate(c)) {
                        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid("TEXT_VALUE");
                    } else if (Character.isLowSurrogate(c)) throw invalid("TEXT_VALUE");
                }
                yield text;
            }
            case "BLOB" -> {
                if (!(value instanceof byte[])) throw invalid("BLOB_VALUE");
                yield value;
            }
            default -> throw invalid("COLUMN_TYPE");
        };
    }
    /**
     * 创建格式规定的 SHA-256 摘要器。
     * @return 全新摘要器
     */
    public static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    /**
     * 追加一个规范值的类型标签、长度和内容。
     * @param hash 当前行流摘要器
     * @param value 已规范化的值
     */
    public static void hash(MessageDigest hash, Object value) {
        if (value == null) { hash.update((byte) 0); return; }
        byte[] bytes;
        if (value instanceof Long number) { hash.update((byte) 1); bytes = ByteBuffer.allocate(8).putLong(number).array(); }
        else if (value instanceof Double number) { hash.update((byte) 2); bytes = ByteBuffer.allocate(8).putDouble(number).array(); }
        else if (value instanceof byte[] blob) { hash.update((byte) 3); bytes = blob; }
        else if (value instanceof String text) { hash.update((byte) 4); bytes = text.getBytes(StandardCharsets.UTF_8); }
        else throw invalid("VALUE_TYPE");
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
    /**
     * 完成摘要并输出小写十六进制。
     * @param digest 当前摘要器
     * @return SHA-256 文本
     */
    public static String finish(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
    /**
     * 逐行校验并计算完整摘要，不拥有游标。
     * @param rows 由调用方关闭的行流
     * @param control 取消及进度控制器
     * @param table 当前表名
     * @return 完整行数及摘要
     * @throws Exception 读取、值校验或取消失败
     */
    public static MigrationVerification.TableDigest digest(MigrationRows rows, MigrationControl control, String table)
            throws Exception {
        var digest = newDigest();
        long count = 0;
        while (rows.next()) {
            control.checkpoint(table, count);
            for (Object value : rows.values()) hash(digest, value);
            count++;
        }
        return new MigrationVerification.TableDigest(count, finish(digest));
    }
    private static IllegalStateException invalid(String code) { return new IllegalStateException(code); }
}
