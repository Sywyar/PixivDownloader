package top.sywyar.pixivdownload.pageeditor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePublications;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 编辑器只持有草稿，正式页面与回滚通过宿主的同一发布事务处理。 */
public final class PageStore {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PageStore.class);
    private final Path directory;
    private final PageDocuments documents;
    private final PagePublications publications;
    private boolean publishing;
    public PageStore(Path directory, PageDocuments documents, PagePublications publications) {
        this.directory = directory.toAbsolutePath().normalize(); this.documents = documents; this.publications = publications;
    }

    private Path file(String id) {
        documents.page(id);
        if (!id.matches("[a-z0-9-]+:[a-zA-Z0-9._-]+")) throw PageDocuments.invalid("PAGE_ID_INVALID");
        return directory.resolve(id.replace(':', '_') + ".json");
    }

    public synchronized ObjectNode read(String id) {
        if (publishing) throw new IllegalStateException("PAGE_REVISION_CONFLICT");
        ObjectNode state = draft(id);
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
                if (!state.isObject() || state.size() != 2 || !state.path("revision").canConvertToLong() || !state.has("draft"))
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
            List<PagePublications.Permission> permissions, List<String> pages, List<Draft> drafts,
            List<PagePublications.HookChange> hooks) { }
    /** 所选页面的草稿修订；恢复内容由正式历史提供，不由此记录指定。 */
    public record Draft(String page, long revision) { }
    public record Decision(long revision, String fingerprint, List<PagePublications.Permission> approvals) { }

    public synchronized Preview prepare(String id, long revision, boolean restoring) {
        return prepare(id, revision, restoring, List.of());
    }

    public synchronized Preview prepare(String id, long revision, boolean restoring, List<Draft> drafts) {
        var selected = selected(id, revision, drafts);
        var plan = plan(candidates(id, revision, selected, restoring), restoring);
        return new Preview(plan.revision(), fingerprint(plan, selected, restoring), plan.impacts(), plan.permissions(),
                plan.changes().stream().map(PagePublications.Change::page).toList(), selected, plan.hooks());
    }

    private static List<Draft> selected(String id, long revision, List<Draft> drafts) {
        if (drafts == null || drafts.size() >= 128) throw PageDocuments.invalid("PAGE_DOCUMENT_BUDGET");
        var selected = new TreeMap<String, Draft>();
        selected.put(id, new Draft(id, revision));
        for (Draft draft : drafts) {
            if (draft == null || draft.page() == null || draft.revision() < 0) throw PageDocuments.invalid("PAGE_DOCUMENT_INVALID");
            if (selected.putIfAbsent(draft.page(), draft) != null) throw PageDocuments.invalid("PAGE_ID_CONFLICT");
        }
        return List.copyOf(selected.values());
    }

    private static String fingerprint(PagePublications.Plan plan, List<Draft> drafts, boolean restoring) {
        try {
            byte[] input = PageDocuments.JSON.writeValueAsBytes(List.of(plan.fingerprint(), drafts, restoring));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        } catch (IOException | java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private Map<String, ObjectNode> candidates(String id, long revision, List<Draft> selected, boolean restoring) {
        var states = new LinkedHashMap<String, ObjectNode>();
        states.put(id, expected(id, revision));
        for (Draft draft : selected) if (!draft.page().equals(id)) states.put(draft.page(), expected(draft.page(), draft.revision()));
        // 只保留候选内容和提交后的草稿写入状态，避免批量恢复同时持有无关草稿副本。
        states.forEach((page, state) -> {
            if (restoring) state.remove("draft");
            if (!page.equals(id)) {
                state.remove("published");
                if (!restoring) state.remove("previous");
            }
        });
        return states;
    }

    private static JsonNode source(ObjectNode state, boolean restoring) {
        JsonNode source = restoring ? state.get("previous") : state.path("draft");
        if (source == null) throw PageDocuments.invalid("PAGE_PREVIOUS_UNAVAILABLE");
        return source;
    }

    private PagePublications.Plan plan(Map<String, ObjectNode> states, boolean restoring) {
        long revision = states.values().iterator().next().path("publicationRevision").asLong();
        var changes = new ArrayList<PagePublications.Change>();
        states.forEach((page, state) -> changes.add(change(page, source(state, restoring))));
        var included = new HashSet<>(states.keySet());
        while (true) {
            try { return publications.prepare(revision, changes); }
            catch (PagePublications.Conflict conflict) {
                int before = changes.size();
                for (String page : conflict.pages()) {
                    if (!included.add(page)) continue;
                    if (changes.size() >= 128) throw PageDocuments.invalid("PAGE_DOCUMENT_BUDGET");
                    var published = publications.read(page).orElseThrow(() -> PageDocuments.invalid("PAGE_REVISION_CONFLICT"));
                    if (published.revision() != revision) throw PageDocuments.invalid("PAGE_REVISION_CONFLICT");
                    // 只刷新真正冲突的正式内容；未选择的草稿始终不参与发布。
                    changes.add(change(page, PageDocuments.parse(published.document())));
                }
                if (changes.size() == before) throw conflict;
            }
        }
    }

    private PagePublications.Change change(String id, JsonNode source) {
        ObjectNode view = documents.validate(id, source, source, true);
        view.put("defaultLayout", documents.usesDefaultLayout(view));
        return new PagePublications.Change(id, source.toString(), view.toString());
    }

    public ObjectNode publish(String id, long revision, Decision decision, boolean restoring) {
        return publish(id, revision, decision, restoring, List.of());
    }

    public ObjectNode publish(String id, long revision, Decision decision, boolean restoring, List<Draft> drafts) {
        if (decision == null || decision.fingerprint() == null || decision.approvals() == null)
            throw PageDocuments.invalid("PAGE_PUBLICATION_STALE");
        Map<String, ObjectNode> states;
        PagePublications.Plan plan;
        synchronized (this) {
            var selected = selected(id, revision, drafts);
            states = candidates(id, revision, selected, restoring);
            plan = plan(states, restoring);
            if (decision.revision() != plan.revision() || !decision.fingerprint().equals(fingerprint(plan, selected, restoring)))
                throw PageDocuments.invalid("PAGE_PUBLICATION_STALE");
            // 冻结草稿事务但不持锁等待宿主排空，其它已准入请求须能拒绝退出。
            publishing = true;
        }
        try {
            ObjectNode state = states.get(id);
            JsonNode source = source(state, restoring).deepCopy();
            JsonNode previous = source.equals(state.get("published")) ? state.get("previous") : state.get("published");
            long committed = publications.publish(plan, decision.approvals());
            if (restoring) {
                var failures = new ArrayList<String>();
                for (var entry : states.entrySet()) {
                    String page = entry.getKey();
                    ObjectNode restored = entry.getValue();
                    restored.set("draft", source(restored, true).deepCopy());
                    try {
                        ObjectNode saved = write(page, restored.deepCopy());
                        if (page.equals(id)) state = saved;
                    } catch (RuntimeException failure) {
                        failures.add(page);
                        if (page.equals(id)) state.put("draftSaveFailed", true);
                        LOG.warn("Page restoration committed; draft save failed: page={}, publicationRevision={}, reason=PAGE_STORAGE_FAILED, failureType={}",
                                page, committed, failure.getClass().getSimpleName());
                    }
                }
                if (!failures.isEmpty()) state.set("draftSaveFailures", PageDocuments.JSON.valueToTree(failures));
            }
            // 已提交结果直接由本次提交构造，辅助存储或回读故障不能使客户端重放发布。
            state.put("publicationRevision", committed);
            state.set("published", source);
            if (previous == null) state.remove("previous"); else state.set("previous", previous);
            return state;
        } finally { synchronized (this) { publishing = false; } }
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
