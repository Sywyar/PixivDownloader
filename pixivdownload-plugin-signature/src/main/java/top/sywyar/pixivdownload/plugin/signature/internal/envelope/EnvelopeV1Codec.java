package top.sywyar.pixivdownload.plugin.signature.internal.envelope;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 唯一规范的 V1 签名消息 codec。
 */
public final class EnvelopeV1Codec {

    private static final String ARTIFACT_DOMAIN = "PixivDownloader plugin artifact signature v1";
    private static final String MANIFEST_DOMAIN = "PixivDownloader plugin manifest signature v1";
    private static final int FORMAT_VERSION = 1;

    private EnvelopeV1Codec() {
    }

    public static byte[] artifactMessage(String algorithm, String keyId, String pluginId, String version,
                                         long artifactSize, byte[] sha256) {
        requireSha256(sha256);
        return write(out -> {
            writeString(out, ARTIFACT_DOMAIN);
            out.writeInt(FORMAT_VERSION);
            writeString(out, algorithm);
            writeString(out, keyId);
            writeString(out, pluginId);
            writeString(out, version);
            out.writeLong(artifactSize);
            out.write(sha256);
        });
    }

    public static byte[] manifestMessage(String repositoryId, long rawLength, byte[] sha256) {
        requireSha256(sha256);
        return write(out -> {
            writeString(out, MANIFEST_DOMAIN);
            out.writeInt(FORMAT_VERSION);
            writeString(out, repositoryId);
            out.writeLong(rawLength);
            out.write(sha256);
        });
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writer.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode signature envelope", e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void requireSha256(byte[] sha256) {
        if (sha256 == null || sha256.length != 32) {
            throw new IllegalArgumentException("sha256 must be exactly 32 bytes");
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
