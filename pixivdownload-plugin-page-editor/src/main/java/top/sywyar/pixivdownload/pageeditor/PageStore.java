package top.sywyar.pixivdownload.pageeditor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePublications;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** 编辑器只持有草稿，正式页面与回滚通过宿主的同一发布事务处理。 */
public final class PageStore {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PageStore.class);
    private final Path directory;
    private final PageDocuments documents;
    private final PagePublications publications;
    public PageStore(Path directory, PageDocuments documents, PagePublications publications) {
        this.directory = directory.toAbsolutePath().normalize(); this.documents = documents; this.publications = publications;
    }

    private Path file(String id) {
        documents.page(id);
        if (!id.matches("[a-z0-9-]+:[a-zA-Z0-9._-]+")) throw PageDocuments.invalid("PAGE_ID_INVALID");
        return directory.resolve(id.replace(':', '_') + ".json");
    }

    public synchronized ObjectNode read(String id) {
        ObjectNode state = draft(id);
        state.remove(List.of("published", "previous"));
        state.put("publicationRevision", publications.revision());
        publications.read(id).ifPresent(value -> {
            state.set("published", PageDocuments.parse(value.document()));
            if (!value.previousDocument().isEmpty()) state.set("previous", PageDocuments.parse(value.previousDocument()));
        });
        return state;
    }

    private ObjectNode draft(String id) {
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
        state.set("draft", documents.normalize(id, draft, state.path("draft")));
        write(id, state); return read(id);
    }

    public record Preview(long revision, String fingerprint, List<PagePublications.Impact> impacts,
            List<PagePublications.Permission> permissions) { }
    public record Decision(long revision, String fingerprint, List<PagePublications.Permission> approvals) { }

    public synchronized Preview prepare(String id, long revision, boolean restoring) {
        var plan = plan(id, expected(id, revision), restoring);
        return new Preview(plan.revision(), plan.fingerprint(), plan.impacts(), plan.permissions());
    }

    private PagePublications.Plan plan(String id, ObjectNode state, boolean restoring) {
        JsonNode source = restoring ? state.get("previous") : state.path("draft");
        if (source == null) throw PageDocuments.invalid("PAGE_PREVIOUS_UNAVAILABLE");
        ObjectNode view = documents.validate(id, source, source, true);
        view.put("defaultLayout", documents.usesDefaultLayout(view));
        return publications.prepare(state.path("publicationRevision").asLong(),
                List.of(new PagePublications.Change(id, source.toString(), view.toString())));
    }

    public synchronized ObjectNode publish(String id, long revision, Decision decision, boolean restoring) {
        if (decision == null || decision.fingerprint() == null || decision.approvals() == null)
            throw PageDocuments.invalid("PAGE_PUBLICATION_STALE");
        ObjectNode state = expected(id, revision);
        var plan = plan(id, state, restoring);
        if (decision.revision() != plan.revision() || !decision.fingerprint().equals(plan.fingerprint()))
            throw PageDocuments.invalid("PAGE_PUBLICATION_STALE");
        JsonNode source = (restoring ? state.get("previous") : state.get("draft")).deepCopy();
        JsonNode previous = state.get("published");
        long committed = publications.publish(plan, decision.approvals());
        if (restoring) {
            state.set("draft", source);
            try { state = write(id, state.deepCopy()); }
            catch (RuntimeException failure) {
                state.put("draftSaveFailed", true);
                LOG.warn("Page restoration committed; draft save failed: page={}, publicationRevision={}, reason=PAGE_STORAGE_FAILED, failureType={}",
                        id, committed, failure.getClass().getSimpleName());
            }
        }
        // 已提交结果直接由本次提交构造，辅助存储或回读故障不能使客户端重放发布。
        state.put("publicationRevision", committed);
        state.set("published", source);
        if (previous == null) state.remove("previous"); else state.set("previous", previous);
        return state;
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

    private ObjectNode expected(String id, long revision) {
        ObjectNode state = read(id);
        if (state.path("revision").asLong() != revision) throw new IllegalStateException("PAGE_REVISION_CONFLICT");
        return state;
    }

    private ObjectNode write(String id, ObjectNode state) {
        state.remove(List.of("published", "previous", "publicationRevision"));
        state.put("revision", Math.addExact(state.path("revision").asLong(), 1));
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("linked page directory");
            retainPreviousState(id);
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

    /** 首次覆盖旧编辑器存档前保留原始字节；它只供管理员取回内容，不参与正式安全状态。 */
    private void retainPreviousState(String id) throws IOException {
        Path source = file(id);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid page file");
        byte[] bytes;
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(3 * PageDocuments.MAX_BYTES + 1025);
        }
        if (bytes.length > 3 * PageDocuments.MAX_BYTES + 1024) throw new IOException("page state budget");
        JsonNode state = PageDocuments.JSON.readTree(bytes);
        if (!state.has("published") && !state.has("previous")) return;
        Path retained = directory.resolve(id.replace(':', '_') + ".retained.json");
        if (Files.exists(retained, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS) || Files.size(retained) != bytes.length
                    || !java.util.Arrays.equals(Files.readAllBytes(retained), bytes))
                throw new IOException("retained page state conflict");
            return;
        }
        try (var channel = FileChannel.open(retained, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
