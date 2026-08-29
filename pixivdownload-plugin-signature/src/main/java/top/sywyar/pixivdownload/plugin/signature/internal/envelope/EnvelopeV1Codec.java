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
    private static final String REPOSITORY_UPDATE_DOMAIN = "pixivdownloader-repository-update-v1";
    private static final String PLUGIN_REVOCATIONS_DOMAIN = "pixivdownloader-plugin-revocations-v1";
    private static final String IDENTITY_MIGRATION_DOMAIN =
            "PixivDownloader plugin identity migration signature v1";
    private static final String REPOSITORY_IDENTITY_MIGRATION_DOMAIN =
            "PixivDownloader plugin repository identity migration signature v1";
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

    public static byte[] repositoryUpdateMessage(String repositoryId, long sequence,
                                                 long rawLength, byte[] sha256) {
        return signedDocumentMessage(REPOSITORY_UPDATE_DOMAIN, repositoryId, sequence, rawLength, sha256);
    }

    public static byte[] pluginRevocationsMessage(String repositoryId, long sequence,
                                                  long rawLength, byte[] sha256) {
        return signedDocumentMessage(PLUGIN_REVOCATIONS_DOMAIN, repositoryId, sequence, rawLength, sha256);
    }

    private static byte[] signedDocumentMessage(String domain, String repositoryId, long sequence,
                                                long rawLength, byte[] sha256) {
        requireSha256(sha256);
        return write(out -> {
            writeString(out, domain);
            out.writeInt(FORMAT_VERSION);
            writeString(out, repositoryId);
            out.writeLong(sequence);
            out.writeLong(rawLength);
            out.write(sha256);
        });
    }

    public static byte[] identityMigrationMessage(
            String algorithm,
            String signingKeyId,
            String fromPluginId,
            String fromSource,
            String fromRepositoryId,
            boolean fromOfficialRepository,
            String fromPublisher,
            String fromKeyId,
            String toPluginId,
            String toSource,
            String toRepositoryId,
            boolean toOfficialRepository,
            String toPublisher,
            String toKeyId,
            String version,
            long artifactSize,
            byte[] sha256) {
        requireSha256(sha256);
        return write(out -> {
            writeString(out, IDENTITY_MIGRATION_DOMAIN);
            out.writeInt(FORMAT_VERSION);
            writeString(out, algorithm);
            writeString(out, signingKeyId);
            writeString(out, fromPluginId);
            writeString(out, fromSource);
            writeString(out, fromRepositoryId);
            out.writeBoolean(fromOfficialRepository);
            writeString(out, fromPublisher);
            writeString(out, fromKeyId);
            writeString(out, toPluginId);
            writeString(out, toSource);
            writeString(out, toRepositoryId);
            out.writeBoolean(toOfficialRepository);
            writeString(out, toPublisher);
            writeString(out, toKeyId);
            writeString(out, version);
            out.writeLong(artifactSize);
            out.write(sha256);
        });
    }

    public static byte[] repositoryIdentityMigrationMessage(
            String algorithm,
            String signingKeyId,
            String reason,
            String fromPluginId,
            String fromSource,
            String fromRepositoryId,
            boolean fromOfficialRepository,
            String fromPublisher,
            String fromKeyId,
            String toPluginId,
            String toSource,
            String toRepositoryId,
            boolean toOfficialRepository,
            String toPublisher,
            String toKeyId,
            String version,
            long artifactSize,
            byte[] sha256) {
        requireSha256(sha256);
        return write(out -> {
            writeString(out, REPOSITORY_IDENTITY_MIGRATION_DOMAIN);
            out.writeInt(FORMAT_VERSION);
            writeString(out, algorithm);
            writeString(out, signingKeyId);
            writeString(out, reason);
            writeString(out, fromPluginId);
            writeString(out, fromSource);
            writeString(out, fromRepositoryId);
            out.writeBoolean(fromOfficialRepository);
            writeString(out, fromPublisher);
            writeString(out, fromKeyId);
            writeString(out, toPluginId);
            writeString(out, toSource);
            writeString(out, toRepositoryId);
            out.writeBoolean(toOfficialRepository);
            writeString(out, toPublisher);
            writeString(out, toKeyId);
            writeString(out, version);
            out.writeLong(artifactSize);
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
