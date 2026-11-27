package top.sywyar.pixivdownload.core.asset.artwork;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

/** 按文件名定位动图首帧，只读取目录头和选中帧；归档内名称不用于创建文件。 */
final class ZipPreviewFrame {
    private static final int MAX_ENTRIES = 500;
    private static final int END_BYTES = 22;
    private static final int CENTRAL_HEADER_BYTES = 46;
    private static final long UINT32_MAX = 0xffff_ffffL;

    private ZipPreviewFrame() {}

    static void copy(Path source, Path target, long maximumBytes) throws IOException {
        try (var archive = new RandomAccessFile(source.toFile(), "r")) {
            var frame = firstFrame(archive);
            archive.seek(frame.offset());
            try (var zip = new ZipInputStream(Channels.newInputStream(archive.getChannel()))) {
                var entry = zip.getNextEntry();
                if (entry == null || entry.isDirectory() || !frame.name().equals(entry.getName()))
                    throw new IOException("Invalid animation frame header");
                if (entry.getSize() > maximumBytes) throw new IOException("Animation frame byte limit exceeded");
                byte[] buffer = new byte[64 * 1024];
                long count = 0;
                try (var out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        checkInterrupted();
                        count += read;
                        if (count > maximumBytes) throw new IOException("Animation frame byte limit exceeded");
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private record Frame(String name, long offset) {}

    private record Directory(long entries, long start, long end) {}

    private static Directory directory(RandomAccessFile archive) throws IOException {
        checkInterrupted();
        long length = archive.length();
        int tailBytes = (int) Math.min(length, END_BYTES + 0xffff);
        var tail = readAt(archive, length - tailBytes, tailBytes);
        int end = tailBytes - END_BYTES;
        while (end >= 0 && !(tail.getInt(end) == 0x06054b50 && end + END_BYTES + u16(tail, end + 20) == tailBytes)) end--;
        if (end < 0) throw new IOException("Missing ZIP central directory");
        if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0 || u16(tail, end + 8) != u16(tail, end + 10))
            throw new IOException("Multi-disk animation ZIP is unsupported");
        long directoryEnd = length - tailBytes + end;
        long entries = u16(tail, end + 10);
        long size = u32(tail, end + 12);
        long offset = u32(tail, end + 16);
        if (entries == 0xffff || size == UINT32_MAX || offset == UINT32_MAX) {
            var locator = readAt(archive, directoryEnd - 20, 20);
            if (locator.getInt(0) != 0x07064b50 || locator.getInt(4) != 0 || locator.getInt(16) != 1)
                throw new IOException("Invalid ZIP64 locator");
            directoryEnd = locator.getLong(8);
            var zip64 = readAt(archive, directoryEnd, 56);
            if (zip64.getInt(0) != 0x06064b50 || zip64.getInt(16) != 0 || zip64.getInt(20) != 0
                    || zip64.getLong(24) != zip64.getLong(32)) throw new IOException("Invalid ZIP64 central directory");
            entries = zip64.getLong(32);
            size = zip64.getLong(40);
            offset = zip64.getLong(48);
        }
        if (entries < 0 || entries > MAX_ENTRIES) throw new IOException("Animation ZIP entry limit exceeded");
        if (size < 0 || offset < 0 || size > directoryEnd - offset) throw new IOException("Invalid ZIP directory bounds");
        return new Directory(entries, offset, offset + size);
    }

    private static Frame firstFrame(RandomAccessFile archive) throws IOException {
        var directory = directory(archive);
        long cursor = directory.start();
        Frame first = null;
        for (long i = 0; i < directory.entries(); i++) {
            checkInterrupted();
            if (directory.end() - cursor < CENTRAL_HEADER_BYTES) throw new IOException("Truncated ZIP directory");
            var header = readAt(archive, cursor, CENTRAL_HEADER_BYTES);
            if (header.getInt(0) != 0x02014b50) throw new IOException("Invalid ZIP entry");
            int nameBytes = u16(header, 28);
            int extraBytes = u16(header, 30);
            long next = cursor + CENTRAL_HEADER_BYTES + nameBytes + extraBytes + u16(header, 32);
            if (next > directory.end()) throw new IOException("Truncated ZIP entry");
            String name = StandardCharsets.UTF_8.newDecoder()
                    .decode(readAt(archive, cursor + CENTRAL_HEADER_BYTES, nameBytes)).toString();
            if (!name.endsWith("/") && (first == null || name.compareTo(first.name()) <= 0)) {
                long position = u32(header, 42);
                if (position == UINT32_MAX) {
                    var extra = readAt(archive, cursor + CENTRAL_HEADER_BYTES + nameBytes, extraBytes);
                    position = zip64Offset(extra, header);
                }
                if (position < 0 || position >= directory.start()) throw new IOException("Invalid ZIP frame offset");
                // 下载端 TreeMap 遇到同名帧时保留物理顺序中最后一个。
                if (first == null || !name.equals(first.name()) || position > first.offset()) first = new Frame(name, position);
            }
            cursor = next;
        }
        if (cursor != directory.end()) throw new IOException("Inconsistent ZIP directory length");
        if (first == null) throw new IOException("Missing animation frame");
        return first;
    }

    private static long zip64Offset(ByteBuffer extra, ByteBuffer header) throws IOException {
        for (int at = 0; at <= extra.limit() - 4;) {
            int type = u16(extra, at);
            int bytes = u16(extra, at + 2);
            at += 4;
            if (bytes > extra.limit() - at) break;
            if (type == 1) {
                int skip = (u32(header, 24) == UINT32_MAX ? 8 : 0) + (u32(header, 20) == UINT32_MAX ? 8 : 0);
                if (bytes >= skip + 8) return extra.getLong(at + skip);
                break;
            }
            at += bytes;
        }
        throw new IOException("Missing ZIP64 frame offset");
    }

    private static ByteBuffer readAt(RandomAccessFile input, long position, int size) throws IOException {
        if (position < 0) throw new IOException("Invalid ZIP offset");
        byte[] bytes = new byte[size];
        input.seek(position);
        input.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int u16(ByteBuffer bytes, int index) { return Short.toUnsignedInt(bytes.getShort(index)); }
    private static long u32(ByteBuffer bytes, int index) { return Integer.toUnsignedLong(bytes.getInt(index)); }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Image decoding interrupted");
    }
}
