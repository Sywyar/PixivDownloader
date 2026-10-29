package top.sywyar.pixivdownload.transfer.archive;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 固定类型标签与逐行预算，不通过 JSON 数字或 Java 序列化交换数据库值。 */
final class DatabaseRowsCodec {
    private static final int MAGIC = 0x50445231;
    private static final int MAX_VALUE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ROW_BYTES = 32 * 1024 * 1024;
    private static final int MAX_COLUMNS = 4096;
    private DatabaseRowsCodec() {}

    static MigrationVerification.TableDigest write(OutputStream output, TableSpec table, MigrationRows rows,
                      MigrationVerification.TableDigest expected, MigrationControl control,
                      java.util.function.BiFunction<TableSpec, List<Object>, List<Object>> normalize) throws Exception {
        requireColumns(table);
        var data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeInt(table.columns().size());
        data.writeLong(expected.rows());
        var digest = MigrationValues.newDigest();
        var sourceDigest = MigrationValues.newDigest();
        long count = 0;
        while (rows.next()) {
            control.checkpoint(table.name(), count);
            if (count >= expected.rows() || rows.values().size() != table.columns().size()) throw invalid();
            for (int column = 0; column < table.columns().size(); column++)
                MigrationValues.hash(sourceDigest, MigrationValues.canonical(rows.values().get(column), table.columns().get(column).type()));
            List<Object> values = normalize.apply(table, rows.values());
            if (values == null || values.size() != table.columns().size()) throw invalid();
            int remaining = MAX_ROW_BYTES;
            for (int column = 0; column < table.columns().size(); column++) {
                Object value = MigrationValues.canonical(values.get(column), table.columns().get(column).type());
                if (value == null) data.writeByte(0);
                else if (value instanceof Long number) { data.writeByte(1); data.writeLong(number); remaining -= 8; }
                else if (value instanceof Double number) { data.writeByte(2); data.writeDouble(number); remaining -= 8; }
                else {
                    byte[] bytes;
                    if (value instanceof byte[] blob) { data.writeByte(3); bytes = blob; }
                    else {
                        String text = (String) value;
                        if (text.length() > MAX_VALUE_BYTES) throw limit();
                        data.writeByte(4); bytes = text.getBytes(StandardCharsets.UTF_8);
                    }
                    if (bytes.length > Math.min(MAX_VALUE_BYTES, remaining)) throw limit();
                    data.writeInt(bytes.length); data.write(bytes); remaining -= bytes.length;
                }
                if (remaining < 0) throw limit();
                MigrationValues.hash(digest, value);
            }
            count++;
        }
        if (!new MigrationVerification.TableDigest(count, MigrationValues.finish(sourceDigest)).equals(expected)) throw invalid();
        data.flush();
        return new MigrationVerification.TableDigest(count, MigrationValues.finish(digest));
    }

    /** 关闭游标会关闭输入流；读取 EOF 前必须完整核对预期行数、类型与摘要。 */
    static MigrationRows read(InputStream input, TableSpec table, MigrationVerification.TableDigest expected,
                              MigrationControl control) throws IOException {
        requireColumns(table);
        var data = new DataInputStream(input);
        if (data.readInt() != MAGIC || data.readInt() != table.columns().size() || data.readLong() != expected.rows()) throw invalid();
        return new MigrationRows() {
            private final java.security.MessageDigest digest = MigrationValues.newDigest();
            private long count;
            private List<Object> current;
            private boolean complete;
            @Override public boolean next() throws Exception {
                current = null;
                if (complete) return false;
                control.checkpoint(table.name(), count);
                if (count == expected.rows()) {
                    if (data.read() != -1 || !MigrationValues.finish(digest).equals(expected.sha256())) throw invalid();
                    complete = true;
                    return false;
                }
                List<Object> values = new ArrayList<>(table.columns().size());
                int remaining = MAX_ROW_BYTES;
                for (var column : table.columns()) {
                    int tag = data.readUnsignedByte();
                    Object value;
                    if (tag == 0) value = null;
                    else if (tag == 1) { value = data.readLong(); remaining -= 8; }
                    else if (tag == 2) { value = data.readDouble(); remaining -= 8; }
                    else if (tag == 3 || tag == 4) {
                        int size = data.readInt();
                        if (size < 0 || size > Math.min(MAX_VALUE_BYTES, remaining)) throw limit();
                        byte[] bytes = data.readNBytes(size);
                        if (bytes.length != size) throw invalid();
                        remaining -= size;
                        value = tag == 3 ? bytes : StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes)).toString();
                    } else throw invalid();
                    if (remaining < 0) throw limit();
                    value = MigrationValues.canonical(value, column.type());
                    MigrationValues.hash(digest, value);
                    values.add(value);
                }
                current = Collections.unmodifiableList(values);
                count++;
                return true;
            }
            @Override public List<Object> values() {
                if (current == null) throw new IllegalStateException("transfer.database-no-row");
                return current;
            }
            @Override public void close() throws IOException { current = null; data.close(); }
        };
    }
    private static void requireColumns(TableSpec table) throws IOException {
        if (table.columns().isEmpty() || table.columns().size() > MAX_COLUMNS) throw limit();
    }
    private static IOException invalid() { return new IOException("transfer.database-invalid"); }
    private static IOException limit() { return new IOException("transfer.database-limit"); }
}
