package top.sywyar.pixivdownload.pageeditor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePublicationSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

/** 一次原子替换提交草稿、正式版及上一版，失败时保留原文件。 */
public final class PageStore implements PagePublicationSource {
    private final Path directory;
    private final PageDocuments documents;
    public PageStore(Path directory, PageDocuments documents) {
        this.directory = directory.toAbsolutePath().normalize(); this.documents = documents;
    }

    private Path file(String id) {
        documents.page(id);
        if (!id.matches("[a-z0-9-]+:[a-zA-Z0-9._-]+")) throw PageDocuments.invalid("PAGE_ID_INVALID");
        return directory.resolve(id.replace(':', '_') + ".json");
    }

    public synchronized ObjectNode read(String id) {
        Path path = file(id);
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                ObjectNode state = PageDocuments.JSON.createObjectNode().put("revision", 0);
                state.set("draft", documents.initial(id)); return state;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid page file");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(3 * PageDocuments.MAX_BYTES + 1025);
                if (bytes.length > 3 * PageDocuments.MAX_BYTES + 1024) throw new IOException("page state budget");
                JsonNode state = PageDocuments.JSON.readTree(bytes);
                if (!state.isObject() || !state.path("revision").canConvertToLong() || !state.has("draft"))
                    throw new IOException("invalid page state");
                return (ObjectNode) state;
            }
        } catch (IOException failure) { throw new IllegalStateException("PAGE_STORAGE_FAILED", failure); }
    }

    public synchronized ObjectNode save(String id, long revision, JsonNode draft) {
        ObjectNode state = expected(id, revision);
        documents.validate(id, draft, state.path("draft"), false);
        state.set("draft", draft.deepCopy()); return write(id, state);
    }

    public synchronized ObjectNode publish(String id, long revision) {
        ObjectNode state = expected(id, revision);
        documents.validate(id, state.path("draft"), state.path("draft"), true);
        if (state.has("published")) state.set("previous", state.get("published"));
        state.set("published", state.path("draft").deepCopy()); return write(id, state);
    }

    public synchronized ObjectNode restore(String id, long revision) {
        ObjectNode state = expected(id, revision);
        JsonNode previous = state.get("previous");
        if (previous == null) throw PageDocuments.invalid("PAGE_PREVIOUS_UNAVAILABLE");
        documents.validate(id, previous, previous, true);
        state.set("previous", state.get("published")); state.set("published", previous);
        state.set("draft", previous.deepCopy()); return write(id, state);
    }

    public synchronized ObjectNode preview(String id, JsonNode draft) {
        return documents.validate(id, draft, read(id).path("draft"), false);
    }

    /** 读取 owner 的默认草稿，不覆盖用户存档；应用和保存均由用户显式执行。 */
    public ObjectNode defaults(String id) {
        ObjectNode draft = documents.initial(id);
        documents.validate(id, draft, draft, false);
        return draft;
    }

    @Override public synchronized Optional<String> published(String id) {
        JsonNode source = read(id).get("published");
        if (source == null) return Optional.empty();
        ObjectNode view = documents.validate(id, source, source, true);
        return documents.usesDefaultLayout(view) ? Optional.empty() : Optional.of(view.toString());
    }

    private ObjectNode expected(String id, long revision) {
        ObjectNode state = read(id);
        if (state.path("revision").asLong() != revision) throw new IllegalStateException("PAGE_REVISION_CONFLICT");
        return state;
    }

    private ObjectNode write(String id, ObjectNode state) {
        state.put("revision", Math.addExact(state.path("revision").asLong(), 1));
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("linked page directory");
            temporary = Files.createTempFile(directory, ".page-", ".tmp");
            byte[] bytes = state.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return state;
        } catch (IOException failure) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw new IllegalStateException("PAGE_STORAGE_FAILED", failure);
        }
    }
}
