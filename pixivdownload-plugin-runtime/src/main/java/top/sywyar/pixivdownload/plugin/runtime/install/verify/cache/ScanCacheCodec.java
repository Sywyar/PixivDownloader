package top.sywyar.pixivdownload.plugin.runtime.install.verify.cache;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginClassSpace;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier.VerificationUsage;

import java.io.*;
import java.util.*;

/** 有界纯值编码，不反序列化 Java 对象或保存任何可执行内容。 */
final class ScanCacheCodec {
    private ScanCacheCodec() { }

    static byte[] encode(String key, VerificationUsage usage) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new FilterOutputStream(bytes) {
            private int written;
            @Override public void write(int value) throws IOException {
                requireCapacity(1); out.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                requireCapacity(length); out.write(value, offset, length);
            }
            private void requireCapacity(int length) throws IOException {
                if (length > ScanCacheFiles.MAX_ENTRY_BYTES - 32 - written) throw new IOException("CACHE_ENTRY_CAPACITY");
                written += length;
            }
        })) {
            out.writeUTF(key);
            out.writeInt(usage.entryCount());
            out.writeLong(usage.totalUncompressedBytes());
            var space = usage.classSpace();
            out.writeInt(space.packagesByArchive().size());
            for (var entry : space.packagesByArchive().entrySet()) {
                out.writeUTF(entry.getKey()); writeSet(out, entry.getValue());
            }
            out.writeInt(space.archiveDigests().size());
            for (var entry : space.archiveDigests().entrySet()) {
                out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue());
            }
            writeSet(out, space.multiReleaseArchives());
            writeSet(out, space.implicitClassPaths());
        }
        return bytes.toByteArray();
    }

    static VerificationUsage decode(String key, byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!key.equals(in.readUTF())) throw new IOException("CACHE_IDENTITY");
            int entries = in.readInt(); long total = in.readLong();
            if (entries < 0 || total < 0) throw new IOException("CACHE_USAGE");
            var packages = new HashMap<String, Set<String>>();
            for (int count = readCount(in); count > 0; count--) {
                if (packages.put(in.readUTF(), readSet(in)) != null) throw new IOException("CACHE_DUPLICATE");
            }
            var digests = new HashMap<String, String>();
            for (int count = readCount(in); count > 0; count--) {
                String name = in.readUTF(), digest = in.readUTF();
                if (!digest.matches("[0-9a-f]{64}") || digests.put(name, digest) != null) throw new IOException("CACHE_DIGEST");
            }
            var space = new PluginClassSpace(packages, digests, readSet(in), readSet(in));
            if (in.read() != -1) throw new IOException("CACHE_TRAILING_DATA");
            return new VerificationUsage(entries, total, space);
        }
    }

    private static void writeSet(DataOutputStream out, Set<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) out.writeUTF(value);
    }

    private static Set<String> readSet(DataInputStream in) throws IOException {
        var values = new HashSet<String>();
        for (int count = readCount(in); count > 0; count--) {
            if (!values.add(in.readUTF())) throw new IOException("CACHE_DUPLICATE");
        }
        return values;
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        // 每个编码元素至少占两个字节，不按不可信计数预分配集合。
        if (count < 0 || count > in.available() / 2) throw new IOException("CACHE_COUNT");
        return count;
    }
}
