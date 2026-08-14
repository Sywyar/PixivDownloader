package top.sywyar.pixivdownload.update;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 正式版与每夜版清单各自的已验签最高序号、版本和原始字节摘要。 */
record UpdateTrustState(
        long stableSequence,
        String stableVersion,
        String stableSha256,
        long nightlySequence,
        String nightlyVersion,
        String nightlySha256) {

    private static final long MAX_BYTES = 4 * 1024;

    static UpdateTrustState read(Path path, ObjectMapper mapper) throws IOException {
        if (!Files.exists(path)) {
            return empty();
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_BYTES) {
            throw new IOException("invalid update trust state size: " + size);
        }
        UpdateTrustState state = mapper.readValue(Files.readAllBytes(path), UpdateTrustState.class);
        if (state == null || state.stableSequence < 0 || state.nightlySequence < 0) {
            throw new IOException("negative update trust sequence");
        }
        validateChannel("stable", state.stableSequence, state.stableVersion, state.stableSha256);
        validateChannel("nightly", state.nightlySequence, state.nightlyVersion, state.nightlySha256);
        return state;
    }

    private static void validateChannel(String channel, long sequence, String version, String sha256) throws IOException {
        boolean empty = sequence == 0 && version == null && sha256 == null;
        boolean valid = sequence > 0 && version != null && !version.isBlank()
                && sha256 != null && sha256.matches("(?i)[0-9a-f]{64}");
        if (!empty && !valid) {
            throw new IOException("invalid " + channel + " update trust state");
        }
    }

    private static UpdateTrustState empty() {
        return new UpdateTrustState(0, null, null, 0, null, null);
    }

    UpdateTrustState accept(String channel, long sequence, String version, String sha256) throws IOException {
        boolean nightly = "nightly".equals(channel);
        long currentSequence = nightly ? nightlySequence : stableSequence;
        String currentVersion = nightly ? nightlyVersion : stableVersion;
        String currentSha256 = nightly ? nightlySha256 : stableSha256;
        if (sequence < currentSequence) {
            throw new IOException("update manifest rollback rejected: " + sequence + " < " + currentSequence);
        }
        if (sequence == currentSequence) {
            if (currentSha256 != null && !currentSha256.equals(sha256)) {
                throw new IOException("update manifest sequence was reused with different content: " + sequence);
            }
            return this;
        }
        if (version.equals(currentVersion) && currentSha256 != null && !currentSha256.equals(sha256)) {
            throw new IOException("update version was replaced with different content: " + version);
        }
        return nightly
                ? new UpdateTrustState(stableSequence, stableVersion, stableSha256,
                        sequence, version, sha256)
                : new UpdateTrustState(sequence, version, sha256,
                        nightlySequence, nightlyVersion, nightlySha256);
    }

    void writeIfChanged(Path path, UpdateTrustState previous, ObjectMapper mapper) throws IOException {
        if (equals(previous)) {
            return;
        }
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("update trust state requires a parent directory: " + path);
        }
        Files.createDirectories(parent);
        byte[] bytes = mapper.writeValueAsBytes(this);
        Path temp = Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic update trust state replacement is not supported: " + path, e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
