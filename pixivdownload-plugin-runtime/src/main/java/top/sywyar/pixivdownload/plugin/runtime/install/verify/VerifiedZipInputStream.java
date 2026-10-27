package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** 在完成流式字节计量后逐项核对中央目录，避免扫描器和 JAR loader 看到不同条目。 */
final class VerifiedZipInputStream extends ZipInputStream {
    private static final int LOCAL = 0x04034b50;
    private static final int CENTRAL = 0x02014b50;
    private static final int END = 0x06054b50;
    private final PositionedInput source;
    private final Map<Long, Identity> entries = new HashMap<>();
    private final int maxNameLength;
    private ZipEntry active;
    private long activeOffset;
    private int activeFlags;
    private boolean finished;

    VerifiedZipInputStream(InputStream input, int maxNameLength) {
        super(input);
        // Inflater 回退的预读字节也必须回退位置；只统计实际消费的归档偏移。
        source = new PositionedInput(input, buf.length);
        in = source;
        this.maxNameLength = maxNameLength;
    }

    @Override
    public ZipEntry getNextEntry() throws IOException {
        if (finished) return null;
        closeEntry();
        if (active != null) {
            entries.put(activeOffset, new Identity(active.getName(), activeFlags, active.getMethod(),
                    active.getCrc(), active.getCompressedSize(), active.getSize()));
            active = null;
        }
        activeOffset = source.position;
        byte[] prefix = source.readNBytes(8);
        source.unread(prefix);
        if (prefix.length < 8) throw malformed();
        ByteBuffer header = littleEndian(prefix);
        if (header.getInt(0) == LOCAL) {
            activeFlags = Short.toUnsignedInt(header.getShort(6));
            active = super.getNextEntry();
            if (active == null) throw malformed();
            return active;
        }
        verifyCentralDirectory();
        finished = true;
        return null;
    }

    private void verifyCentralDirectory() throws IOException {
        long centralOffset = source.position;
        int count = entries.size();
        for (int index = 0; index < count; index++) {
            ByteBuffer header = readHeader(46);
            if (header.getInt(0) != CENTRAL) throw malformed();
            long offset = unsignedInt(header, 42);
            Identity local = entries.remove(offset);
            int nameBytes = Short.toUnsignedInt(header.getShort(28));
            if (local == null || nameBytes > (long) maxNameLength * 4) throw malformed();
            String name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readExactly(nameBytes))).toString();
            if (!local.name.equals(name)
                    || local.flags != Short.toUnsignedInt(header.getShort(8))
                    || local.method != Short.toUnsignedInt(header.getShort(10))
                    || local.crc != unsignedInt(header, 16)
                    || local.compressedBytes != unsignedInt(header, 20)
                    || local.bytes != unsignedInt(header, 24)
                    || header.getShort(34) != 0 || offset == 0xffffffffL
                    || local.compressedBytes == 0xffffffffL || local.bytes == 0xffffffffL) {
                throw malformed();
            }
            ZipSafety.requireRegularEntryType(Short.toUnsignedInt(header.getShort(4)) >>> 8,
                    unsignedInt(header, 38), index);
            source.skipNBytes(Short.toUnsignedInt(header.getShort(30))
                    + Short.toUnsignedInt(header.getShort(32)));
        }
        long centralBytes = source.position - centralOffset;
        ByteBuffer end = readHeader(22);
        if (end.getInt(0) != END || end.getShort(4) != 0 || end.getShort(6) != 0
                || Short.toUnsignedInt(end.getShort(8)) != count
                || Short.toUnsignedInt(end.getShort(10)) != count || count == 0xffff
                || unsignedInt(end, 12) != centralBytes || unsignedInt(end, 16) != centralOffset) {
            throw malformed();
        }
        int commentBytes = Short.toUnsignedInt(end.getShort(20));
        // 私有 JAR 允许尾随填充；只拒绝有完整 22 字节头的结束记录候选。
        int signature = 0;
        int remainingEndBytes = -1;
        long trailingBytes = 0;
        byte[] tail = new byte[8192];
        int read;
        while ((read = source.read(tail)) >= 0) {
            trailingBytes += read;
            for (int i = 0; i < read; i++) {
                if (remainingEndBytes > 0 && --remainingEndBytes == 0) throw malformed();
                signature = (signature >>> 8) | ((tail[i] & 0xff) << 24);
                if (signature == END && remainingEndBytes < 0) remainingEndBytes = 22 - Integer.BYTES;
            }
        }
        if (trailingBytes < commentBytes) throw malformed();
    }

    private ByteBuffer readHeader(int size) throws IOException {
        return littleEndian(readExactly(size));
    }

    private byte[] readExactly(int size) throws IOException {
        byte[] bytes = source.readNBytes(size);
        if (bytes.length != size) throw malformed();
        return bytes;
    }

    private static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static long unsignedInt(ByteBuffer header, int offset) {
        return Integer.toUnsignedLong(header.getInt(offset));
    }

    private static ZipException malformed() {
        return new ZipException("zip local entries and central directory do not match or use an unsupported layout");
    }

    private record Identity(String name, int flags, int method, long crc, long compressedBytes, long bytes) { }

    private static final class PositionedInput extends PushbackInputStream {
        private long position;

        private PositionedInput(InputStream input, int size) { super(input, size); }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) position++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) position += read;
            return read;
        }

        @Override
        public void unread(byte[] bytes, int offset, int length) throws IOException {
            super.unread(bytes, offset, length);
            position -= length;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) return 0;
            byte[] buffer = new byte[(int) Math.min(count, 8192)];
            long skipped = 0;
            while (skipped < count) {
                int read = read(buffer, 0, (int) Math.min(count - skipped, buffer.length));
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }
    }
}
