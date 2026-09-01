package top.sywyar.pixivdownload.plugin.catalog.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 仓库更新序列与最后有效吊销清单的有界、原子本地快照。 */
@Component
public final class PluginCatalogTrustStateStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginCatalogTrustStateStore.class);
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private final Path path;
    private final ObjectMapper mapper = PluginCatalogStrictJson.mapper(false);

    public PluginCatalogTrustStateStore() {
        this(RuntimeFiles.resolvePluginCatalogTrustStatePath());
    }

    public PluginCatalogTrustStateStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public synchronized State read() {
        if (!Files.isRegularFile(path)) return State.empty();
        try {
            long size = Files.size(path);
            if (size <= 0L || size > MAX_BYTES) throw new IOException("state size outside accepted range");
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_BYTES) throw new IOException("state grew beyond the accepted size");
            State state = mapper.readValue(PluginCatalogStrictJson.strictUtf8(bytes), State.class);
            if (state == null || state.schemaVersion() != 1) {
                throw new IOException("unsupported plugin catalog trust state schema");
            }
            return state.normalized();
        } catch (Exception failure) {
            LOGGER.error("Ignoring corrupt plugin catalog trust state {}: {}", path, failure.getMessage());
            return State.empty();
        }
    }

    public synchronized long updateSequence(String repositoryId) {
        return repository(repositoryId).map(RepositoryState::updateSequence).orElse(0L);
    }

    public synchronized Optional<RevocationSnapshot> revocations(String repositoryId) {
        return repository(repositoryId).map(RepositoryState::revocations).filter(value -> value != null);
    }

    public synchronized void acceptUpdateSequence(String repositoryId, long sequence) throws IOException {
        mutate(repositoryId, old -> new RepositoryState(Math.max(old.updateSequence(), sequence), old.revocations()));
    }

    public synchronized void acceptRevocations(String repositoryId, RevocationSnapshot snapshot) throws IOException {
        if (snapshot == null) throw new IOException("revocation snapshot is required");
        if (repositoryId == null || repositoryId.isBlank()) throw new IOException("repositoryId is required");
        State state = read();
        RepositoryState old = state.repositories().getOrDefault(repositoryId, RepositoryState.empty());
        RevocationSnapshot previous = old.revocations();
        if (previous != null && (snapshot.sequence() < previous.sequence()
                || snapshot.sequence() == previous.sequence()
                && !snapshot.documentSha256().equalsIgnoreCase(previous.documentSha256()))) {
            throw new IOException("revocation snapshot rollback or equivocation rejected");
        }
        Map<String, RepositoryState> repositories = new LinkedHashMap<>(state.repositories());
        repositories.put(repositoryId, new RepositoryState(old.updateSequence(), snapshot));
        write(new State(1, repositories));
    }

    private Optional<RepositoryState> repository(String repositoryId) {
        if (repositoryId == null) return Optional.empty();
        return Optional.ofNullable(read().repositories().get(repositoryId));
    }

    private void mutate(String repositoryId, java.util.function.UnaryOperator<RepositoryState> change)
            throws IOException {
        if (repositoryId == null || repositoryId.isBlank()) throw new IOException("repositoryId is required");
        State state = read();
        Map<String, RepositoryState> repositories = new LinkedHashMap<>(state.repositories());
        repositories.put(repositoryId, change.apply(repositories.getOrDefault(repositoryId, RepositoryState.empty())));
        write(new State(1, repositories));
    }

    private void write(State state) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state.normalized());
        if (bytes.length > MAX_BYTES) throw new IOException("plugin catalog trust state exceeds maximum size");
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record State(int schemaVersion, Map<String, RepositoryState> repositories) {
        public State {
            repositories = Map.copyOf(repositories == null ? Map.of() : repositories);
        }

        static State empty() { return new State(1, Map.of()); }

        State normalized() {
            if (schemaVersion != 1) return empty();
            return new State(1, repositories);
        }
    }

    public record RepositoryState(long updateSequence, RevocationSnapshot revocations) {
        static RepositoryState empty() { return new RepositoryState(0L, null); }
    }

    public record RevocationSnapshot(long sequence, String documentSha256, String generatedTime,
                                     String nextUpdate, String verifiedAt, List<RevocationEntry> entries) {
        public RevocationSnapshot {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }
    }

    public record RevocationEntry(String scope, String pluginId, String version, String packageSha256,
                                  String keyId, String publisherId, String action, String reasonCode,
                                  String effectiveTime) { }
}
