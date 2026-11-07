package top.sywyar.pixivdownload.pageeditor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PageStoreTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.AfterEach
    void cleanupDirectory() throws Exception {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
        assertThat(directory).doesNotExist();
    }
    private final Catalog catalog = new Catalog();
    private final TestPublications publications = new TestPublications();
    private ObjectNode publish(PageStore store, String id, long revision) { return commit(store, id, revision, false); }
    private ObjectNode restore(PageStore store, String id, long revision) { return commit(store, id, revision, true); }
    private ObjectNode commit(PageStore store, String id, long revision, boolean restoring) {
        var preview = store.prepare(id, revision, restoring);
        return store.publish(id, revision, new PageStore.Decision(preview.revision(), preview.fingerprint(), preview.permissions()), restoring);
    }
    private Optional<String> published(String id) {
        return publications.read(id).map(PagePublications.Published::projection)
                .filter(view -> !PageDocuments.parse(view).path("defaultLayout").asBoolean());
    }
    /** 正式存储由宿主集成测试覆盖；此替身只检验编辑器委托及草稿行为。 */
    static final class TestPublications implements PagePublications {
        private long revision;
        private final Map<String, Published> values = new HashMap<>();
        Runnable afterCommit = () -> { };
        boolean rejectCommit;
        public long revision() { return revision; }
        public Optional<Published> read(String page) {
            return Optional.ofNullable(values.get(page)).map(value ->
                    new Published(revision, value.document(), value.projection(), value.previousDocument()));
        }
        public Plan prepare(long expected, List<Change> changes) {
            if (expected != revision) throw new IllegalStateException("PAGE_REVISION_CONFLICT");
            return new Plan(expected, Integer.toString(changes.hashCode()), changes, List.of(), List.of(), List.of());
        }
        public long publish(Plan plan, List<Permission> approvals) {
            if (plan.revision() != revision) throw new IllegalStateException("PAGE_REVISION_CONFLICT");
            if (rejectCommit) throw new IllegalStateException("PAGE_PUBLICATION_STALE");
            revision++;
            for (var change : plan.changes()) {
                Published previous = values.get(change.page());
                values.put(change.page(), new Published(revision, change.document(), change.projection(),
                        previous == null ? "" : previous.document().equals(change.document()) ? previous.previousDocument() : previous.document()));
            }
            afterCommit.run();
            return revision;
        }
    }

    private PageStore store() { return new PageStore(directory, new PageDocuments(catalog), publications); }

    @Test @DisplayName("关联页的展示组件缺席或版本不兼容不阻断当前页的标题发布")
    void unrelatedPresentationFailureDoesNotBlockPublication() {
        var shared = new Catalog() {
            @Override public List<AvailablePage> pages() {
                return List.of("owner:page", "owner:other").stream().map(id -> new AvailablePage(
                        new EditablePage(id, "page", "owner", "/" + id.substring(6), true, regions, "", "", List.of(),
                                List.of(new PageInteraction.Target("submit", "submit", "owner:submit", "required", "{}",
                                        List.of(top.sywyar.pixivdownload.plugin.api.security.OperationVerification.INTERACTION), 1))), "pages")).toList();
            }
        };
        var documents = new PageDocuments(shared);
        var store = new PageStore(directory, documents, publications);
        store.save("owner:page", 0, documents.initial("owner:page")); publish(store, "owner:page", 1);
        var other = documents.initial("owner:other"); component(other, "decoration");
        store.save("owner:other", 0, other); publish(store, "owner:other", 1);
        var original = store.read("owner:other");
        for (boolean missing : List.of(true, false)) {
            shared.available = !missing; shared.version = missing ? 1 : 2;
            long revision = store.read("owner:page").path("revision").asLong();
            store.save("owner:page", revision, documents.initial("owner:page").put("title", "title " + missing));
            var plan = store.prepare("owner:page", revision + 1, false);
            assertThat(plan.pages()).containsExactly("owner:page");
            publish(store, "owner:page", revision + 1);
            assertThat(store.read("owner:other").path("published")).isEqualTo(original.path("published"));
            assertThatThrownBy(() -> store.prepare("owner:other", 1, false)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        }
    }

    @Test @DisplayName("操作要求未变化时只发布当前页，保留其它草稿和恢复点")
    void preparesRelatedPublicationsWithoutPublishingOtherDrafts() {
        var shared = new Catalog() {
            @Override public List<AvailablePage> pages() {
                return List.of(sharedPage("owner:a", List.of("first")),
                        sharedPage("owner:b", List.of("first", "second")),
                        sharedPage("owner:c", List.of("second")), sharedPage("owner:d", List.of("independent")));
            }
            private AvailablePage sharedPage(String id, List<String> operations) {
                var targets = operations.stream().map(operation -> new PageInteraction.Target(operation, operation,
                        "owner:" + operation, "required", "{}", List.of(top.sywyar.pixivdownload.plugin.api.security.OperationVerification.INTERACTION), 1)).toList();
                return new AvailablePage(new EditablePage(id, "page", "owner", "/" + id.substring(6), true,
                        regions, "", "", List.of(), targets), "shared-publication");
            }
        };
        var documents = new PageDocuments(shared);
        var store = new PageStore(directory, documents, publications);
        for (String id : List.of("owner:a", "owner:b", "owner:c", "owner:d")) {
            store.save(id, 0, documents.initial(id).put("title", "published"));
            publish(store, id, 1);
        }
        store.save("owner:b", 1, documents.initial("owner:b").put("title", "second publication"));
        publish(store, "owner:b", 2);
        var otherDraft = store.save("owner:b", 2, documents.initial("owner:b").put("title", "unfinished draft"));
        var before = Map.copyOf(publications.values);
        store.save("owner:a", 1, documents.initial("owner:a").put("title", "new title"));
        var plan = store.prepare("owner:a", 2, false);
        assertThat(plan.pages()).containsExactly("owner:a");
        var decision = new PageStore.Decision(plan.revision(), plan.fingerprint(), plan.permissions());
        publications.rejectCommit = true;
        assertThatThrownBy(() -> store.publish("owner:a", 2, decision, false)).hasMessage("PAGE_PUBLICATION_STALE");
        assertThat(publications.values).isEqualTo(before);
        publications.rejectCommit = false;
        store.publish("owner:a", 2, decision, false);
        assertThat(publications.values.get("owner:d")).isEqualTo(before.get("owner:d"));
        var after = store.read("owner:b");
        for (String field : List.of("revision", "draft", "published", "previous"))
            assertThat(after.get(field)).isEqualTo(otherDraft.get(field));
        assertThatThrownBy(() -> store.publish("owner:a", 2, decision, false)).hasMessage("PAGE_PUBLICATION_STALE");
        var restore = store.prepare("owner:a", 2, true);
        assertThat(restore.pages()).containsExactly("owner:a");
        store.publish("owner:a", 2, new PageStore.Decision(restore.revision(), restore.fingerprint(), restore.permissions()), true);
        assertThat(store.read("owner:a").path("published").path("title").asText()).isEqualTo("published");
    }

    private void blockDraftDirectory(Path pages) {
        try {
            Files.move(pages, directory.resolve("saved-pages"));
            Files.writeString(pages, "blocked");
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    @Test @DisplayName("批量发布只使用显式草稿，任一草稿修订变化均使确认失效且保留历史")
    void selectedDraftsAreAtomicAndRevisionBound() {
        var multiple = new Catalog() {
            @Override public List<AvailablePage> pages() {
                return List.of("owner:page", "owner:other", "owner:unselected").stream().map(id -> new AvailablePage(
                        new EditablePage(id, "page", "owner", "/" + id.substring(6), true, regions, "", "", List.of(), List.of()), "pages")).toList();
            }
        };
        var documents = new PageDocuments(multiple);
        var store = new PageStore(directory, documents, publications);
        for (String id : List.of("owner:page", "owner:other", "owner:unselected")) {
            store.save(id, 0, documents.initial(id).put("title", "original")); publish(store, id, 1);
            store.save(id, 1, documents.initial(id).put("title", "edited"));
        }
        var before = Map.copyOf(publications.values);
        var choices = List.of(new PageStore.Draft("owner:other", 2));
        var plan = store.prepare("owner:page", 2, false, choices);
        assertThat(publications.values).isEqualTo(before);
        assertThat(plan.pages()).containsExactly("owner:page", "owner:other");
        var decision = new PageStore.Decision(plan.revision(), plan.fingerprint(), plan.permissions());
        publications.rejectCommit = true;
        assertThatThrownBy(() -> store.publish("owner:page", 2, decision, false, choices)).hasMessage("PAGE_PUBLICATION_STALE");
        assertThat(publications.values).isEqualTo(before);
        publications.rejectCommit = false;
        store.save("owner:other", 2, documents.initial("owner:other").put("title", "edited"));
        assertThatThrownBy(() -> store.publish("owner:page", 2, decision, false, choices)).hasMessage("PAGE_REVISION_CONFLICT");
        var changed = List.of(new PageStore.Draft("owner:other", 3));
        assertThatThrownBy(() -> store.publish("owner:page", 2, decision, false, changed)).hasMessage("PAGE_PUBLICATION_STALE");
        assertThat(publications.values).isEqualTo(before);
        var current = store.prepare("owner:page", 2, false, changed);
        store.publish("owner:page", 2, new PageStore.Decision(current.revision(), current.fingerprint(), current.permissions()), false, changed);
        assertThat(publications.revision()).isEqualTo(plan.revision() + 1);
        for (String id : List.of("owner:page", "owner:other")) {
            assertThat(store.read(id).path("published").path("title").asText()).isEqualTo("edited");
            assertThat(store.read(id).path("previous").path("title").asText()).isEqualTo("original");
        }
        assertThat(store.read("owner:other").path("revision").asLong()).isEqualTo(3);
        assertThat(store.read("owner:unselected").path("draft").path("title").asText()).isEqualTo("edited");
        assertThat(publications.values.get("owner:unselected")).isEqualTo(before.get("owner:unselected"));
        assertThatThrownBy(() -> store.prepare("owner:page", 2, false, List.of(new PageStore.Draft("owner:page", 2))))
                .hasMessage("PAGE_ID_CONFLICT");
    }

    @Test @DisplayName("显式恢复各页历史而非草稿，确认绑定所有草稿修订且失败不部分写入")
    void selectedHistoryRestoresAtomically() {
        var store = restorableStore();
        var selected = List.of(new PageStore.Draft("owner:other", 3));
        var before = Map.copyOf(publications.values);
        var untouched = store.read("owner:unselected");
        var original = store.read("owner:page");
        var other = store.read("owner:other");
        assertThat(original.path("previous").path("title").asText()).isEqualTo("original");
        assertThat(other.path("previous").path("title").asText()).isEqualTo("original");
        var plan = store.prepare("owner:page", 3, true, selected);
        assertThat(plan.pages()).containsExactly("owner:page", "owner:other");
        assertThatThrownBy(() -> store.prepare("owner:page", 3, true,
                List.of(new PageStore.Draft("owner:unselected", 1)))).hasMessage("PAGE_PREVIOUS_UNAVAILABLE");
        var decision = new PageStore.Decision(plan.revision(), plan.fingerprint(), plan.permissions());
        publications.rejectCommit = true;
        assertThatThrownBy(() -> store.publish("owner:page", 3, decision, true, selected)).hasMessage("PAGE_PUBLICATION_STALE");
        assertThat(publications.values).isEqualTo(before);
        assertThat(store.read("owner:page")).isEqualTo(original);
        assertThat(store.read("owner:other")).isEqualTo(other);
        publications.rejectCommit = false;
        store.save("owner:other", 3, other.path("draft"));
        assertThatThrownBy(() -> store.publish("owner:page", 3, decision, true, selected)).hasMessage("PAGE_REVISION_CONFLICT");
        var changed = List.of(new PageStore.Draft("owner:other", 4));
        assertThatThrownBy(() -> store.publish("owner:page", 3, decision, true, changed)).hasMessage("PAGE_PUBLICATION_STALE");
        assertThat(publications.values).isEqualTo(before);
        plan = store.prepare("owner:page", 3, true, changed);
        var restored = store.publish("owner:page", 3,
                new PageStore.Decision(plan.revision(), plan.fingerprint(), plan.permissions()), true, changed);
        assertThat(restored.path("publicationRevision").asLong()).isEqualTo(plan.revision() + 1);
        for (String id : List.of("owner:page", "owner:other")) {
            var state = store.read(id);
            assertThat(state.path("draft").path("title").asText()).isEqualTo("original");
            assertThat(state.path("published")).isEqualTo(state.path("draft"));
            assertThat(state.path("previous").path("title").asText()).isEqualTo("current");
        }
        assertThat(store.read("owner:page").path("revision").asLong()).isEqualTo(4);
        assertThat(store.read("owner:other").path("revision").asLong()).isEqualTo(5);
        untouched.put("publicationRevision", restored.path("publicationRevision").asLong());
        assertThat(store.read("owner:unselected")).isEqualTo(untouched);
    }

    @Test @DisplayName("相同文档的发布确认不能用于恢复，正式修订变化也必须重新确认")
    void historyConfirmationBindsModeAndPublication() {
        var store = restorableStore();
        for (String id : List.of("owner:page", "owner:other")) store.save(id, 3, store.read(id).path("previous"));
        var selected = List.of(new PageStore.Draft("owner:other", 4));
        var publishing = store.prepare("owner:page", 4, false, selected);
        var decision = new PageStore.Decision(publishing.revision(), publishing.fingerprint(), publishing.permissions());
        assertThatThrownBy(() -> store.publish("owner:page", 4, decision, true, selected)).hasMessage("PAGE_PUBLICATION_STALE");
        var restoring = store.prepare("owner:page", 4, true, selected);
        publish(store, "owner:unselected", 1);
        var before = Map.copyOf(publications.values);
        assertThatThrownBy(() -> store.publish("owner:page", 4,
                new PageStore.Decision(restoring.revision(), restoring.fingerprint(), restoring.permissions()), true, selected))
                .hasMessage("PAGE_PUBLICATION_STALE");
        assertThat(publications.values).isEqualTo(before);
        assertThat(store.read("owner:page").path("revision").asLong()).isEqualTo(4);
        assertThat(store.read("owner:other").path("revision").asLong()).isEqualTo(4);
    }

    @Test @DisplayName("批量正式恢复后关联页草稿失败不改变已提交结果或阻断其它草稿保存")
    void selectedHistoryReportsAuxiliaryFailures() throws Exception {
        var store = restorableStore();
        var selected = List.of(new PageStore.Draft("owner:other", 3));
        var plan = store.prepare("owner:page", 3, true, selected);
        Path blocked = directory.resolve("pages/owner_other.json"), retained = directory.resolve("other-draft.json");
        publications.afterCommit = () -> {
            try { Files.move(blocked, retained); Files.createDirectory(blocked); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        };
        var restored = store.publish("owner:page", 3,
                new PageStore.Decision(plan.revision(), plan.fingerprint(), plan.permissions()), true, selected);
        assertThat(restored.path("draftSaveFailed").asBoolean()).isFalse();
        assertThat(restored.path("draftSaveFailures")).isEqualTo(PageDocuments.JSON.readTree("[\"owner:other\"]"));
        assertThat(restored.path("revision").asLong()).isEqualTo(4);
        assertThat(restored.path("publicationRevision").asLong()).isEqualTo(plan.revision() + 1);
        Files.delete(blocked); Files.move(retained, blocked);
        assertThat(store.read("owner:page").path("draft").path("title").asText()).isEqualTo("original");
        var other = store.read("owner:other");
        assertThat(other.path("published").path("title").asText()).isEqualTo("original");
        assertThat(other.path("draft").path("title").asText()).isEqualTo("unfinished");
        assertThat(other.path("previous").path("title").asText()).isEqualTo("current");
        assertThat(other.path("revision").asLong()).isEqualTo(3);
        store.save("owner:other", 3, other.path("published"));
        assertThat(publications.revision()).isEqualTo(plan.revision() + 1);
    }

    private PageStore restorableStore() {
        var multiple = new Catalog() {
            @Override public List<AvailablePage> pages() {
                return List.of("owner:page", "owner:other", "owner:unselected").stream().map(id -> new AvailablePage(
                        new EditablePage(id, "page", "owner", "/" + id.substring(6), true, regions, "", "", List.of(), List.of()), "pages")).toList();
            }
        };
        var documents = new PageDocuments(multiple);
        var store = new PageStore(directory.resolve("pages"), documents, publications);
        for (String id : List.of("owner:page", "owner:other")) {
            store.save(id, 0, documents.initial(id).put("title", "original")); publish(store, id, 1);
            store.save(id, 1, documents.initial(id).put("title", "current")); publish(store, id, 2);
            store.save(id, 2, documents.initial(id).put("title", "unfinished"));
        }
        store.save("owner:unselected", 0, documents.initial("owner:unselected").put("title", "untouched"));
        return store;
    }

    @Test @DisplayName("正式发布提交后不再依赖草稿写盘或回读，原回滚点保持可用")
    void committedPublicationDoesNotWriteDraftAgain() throws Exception {
        Path pages = directory.resolve("pages");
        var store = new PageStore(pages, new PageDocuments(catalog), publications);
        var first = source().put("title", "first");
        var saved = store.save("owner:page", 0, first);
        var published = publish(store, "owner:page", saved.path("revision").asLong());
        var second = source().put("title", "second");
        saved = store.save("owner:page", published.path("revision").asLong(), second);
        long revision = saved.path("revision").asLong();
        publications.afterCommit = () -> blockDraftDirectory(pages);
        var result = publish(store, "owner:page", revision);
        assertThat(result.path("publicationRevision").asLong()).isEqualTo(2);
        assertThat(result.path("revision").asLong()).isEqualTo(revision);
        assertThat(result.path("published")).isEqualTo(second);
        assertThat(result.path("previous")).isEqualTo(first);
        assertThat(result.path("draftSaveFailed").asBoolean()).isFalse();
        assertThat(PageDocuments.parse(publications.read("owner:page").orElseThrow().previousDocument())).isEqualTo(first);
        Files.delete(pages); Files.move(directory.resolve("saved-pages"), pages);
        assertThat(store.read("owner:page")).isEqualTo(result);
    }

    @Test @DisplayName("恢复提交后的草稿故障返回正式修订，重试保存不重复发布或损失回滚点")
    void committedRestoreCanRetryDraftSave() throws Exception {
        Path pages = directory.resolve("pages");
        var store = new PageStore(pages, new PageDocuments(catalog), publications);
        var first = source().put("title", "first");
        var state = store.save("owner:page", 0, first);
        state = publish(store, "owner:page", state.path("revision").asLong());
        var second = source().put("title", "second");
        state = store.save("owner:page", state.path("revision").asLong(), second);
        state = publish(store, "owner:page", state.path("revision").asLong());
        long revision = state.path("revision").asLong();
        var preview = store.prepare("owner:page", revision, true);
        var decision = new PageStore.Decision(preview.revision(), preview.fingerprint(), preview.permissions());
        publications.rejectCommit = true;
        assertThatThrownBy(() -> store.publish("owner:page", revision, decision, true)).hasMessage("PAGE_PUBLICATION_STALE");
        assertThat(store.read("owner:page")).isEqualTo(state);
        publications.rejectCommit = false;
        publications.afterCommit = () -> blockDraftDirectory(pages);
        var restored = store.publish("owner:page", revision, decision, true);
        assertThat(restored.path("publicationRevision").asLong()).isEqualTo(3);
        assertThat(restored.path("revision").asLong()).isEqualTo(revision);
        assertThat(restored.path("draftSaveFailed").asBoolean()).isTrue();
        assertThat(restored.path("published")).isEqualTo(first);
        assertThat(restored.path("draft")).isEqualTo(first);
        assertThat(restored.path("previous")).isEqualTo(second);
        Files.delete(pages); Files.move(directory.resolve("saved-pages"), pages);
        assertThat(store.read("owner:page").path("draft")).isEqualTo(second);
        assertThatThrownBy(() -> store.publish("owner:page", revision, decision, true)).hasMessage("PAGE_PUBLICATION_STALE");
        var saved = store.save("owner:page", revision, restored.path("draft"));
        assertThat(saved.path("draft")).isEqualTo(first);
        assertThat(saved.path("published")).isEqualTo(first);
        assertThat(saved.path("previous")).isEqualTo(second);
        assertThat(saved.path("publicationRevision").asLong()).isEqualTo(3);
        assertThat(saved.has("draftSaveFailed")).isFalse();
    }
    @Test
    @DisplayName("草稿存储拒绝额外发布字段，不迁移或覆盖无效状态")
    void rejectsPublicationFieldsInDraftState() throws Exception {
        var original = PageDocuments.JSON.createObjectNode().put("revision", 4);
        original.set("draft", source()); original.set("published", source()); original.set("previous", source());
        byte[] bytes = original.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(directory.resolve("owner_page.json"), bytes);
        var store = store();
        assertThatThrownBy(() -> store.read("owner:page")).hasMessage("PAGE_STORAGE_FAILED");
        assertThatThrownBy(() -> store.save("owner:page", 4, source())).hasMessage("PAGE_STORAGE_FAILED");
        assertThat(Files.readAllBytes(directory.resolve("owner_page.json"))).isEqualTo(bytes);
    }
    private ObjectNode source() { return new PageDocuments(catalog).initial("owner:page"); }
    private static ArrayNode children(JsonNode source) {
        return (ArrayNode) source.path("project").path("pages").get(0).path("frames").get(0).path("component").path("components");
    }
    private static void component(JsonNode source, String instance) {
        children(source).addObject().put("type", "page-component").put("componentType", "example:checklist")
                .put("instanceId", instance).put("configVersion", 1).set("configuration", PageDocuments.parse("{\"items\":[\"one\"]}"));
    }
    @Test @DisplayName("未修改的页面保存和发布后仍使用默认布局，读取不改写存档")
    void unchangedPublicationKeepsDefaultLayoutAndStoredSource() throws Exception {
        catalog.project = """
                {"format":1,"project":{"pages":[{"frames":[{"component":{"type":"wrapper","style":{"display":"flex"},
                "components":[{"type":"page-region","region":"required"}]}}]}]}}
                """;
        var store = store();
        assertThat(published("owner:page")).isEmpty();
        assertThat(Files.exists(directory.resolve("owner_page.json"))).isFalse();
        var source = source();
        var root = (ObjectNode) source.at("/project/pages/0/frames/0/component");
        root.putObject("attributes").put("id", "generated-wrapper");
        var rule = ((ObjectNode) source.path("project")).putArray("styles").addObject().put("mediaText", "");
        rule.putArray("selectors").add("#generated-wrapper");
        rule.putObject("style").put("display", "flex");
        ((ObjectNode) children(source).get(0)).putObject("attributes").put("id", "generated-region");
        store.save("owner:page", 0, source);
        assertThat(published("owner:page")).isEmpty();
        publish(store, "owner:page", 1);
        byte[] saved = Files.readAllBytes(directory.resolve("owner_page.json"));
        assertThat(published("owner:page")).isEmpty();
        assertThat(store().read("owner:page").get("published")).isEqualTo(source);
        assertThat(Files.readAllBytes(directory.resolve("owner_page.json"))).isEqualTo(saved);
    }
    @Test @DisplayName("标题、样式、响应式规则和结构修改均保留自定义发布")
    void meaningfulChangesKeepCustomPublication() {
        var title = source().put("title", "Custom title");
        var inline = source();
        ((ObjectNode) children(inline).get(0)).putObject("style").put("padding", "20px");
        var responsive = source();
        ((ObjectNode) children(responsive).get(0)).putObject("attributes").put("id", "region");
        var rule = ((ObjectNode) responsive.path("project")).putArray("styles").addObject()
                .put("mediaText", "(max-width: 600px)");
        rule.putArray("selectors").add("#region"); rule.putObject("style").put("padding", "10px");
        var layout = source();
        JsonNode region = children(layout).remove(0);
        children(layout).addObject().put("type", "page-box").putArray("components").add(region);
        long revision = 0;
        for (var source : List.of(title, inline, responsive, layout)) {
            var store = store();
            revision = store.save("owner:page", revision, source).path("revision").asLong();
            revision = publish(store, "owner:page", revision).path("revision").asLong();
            assertThat(published("owner:page")).isPresent();
        }
    }
    @Test @DisplayName("默认布局与自定义发布之间可恢复，保留两版源文档")
    void defaultPublicationCanRestorePreviousCustomizationAndReturnToDefault() {
        var store = store();
        store.save("owner:page", 0, source().put("title", "Custom title")); publish(store, "owner:page", 1);
        store.save("owner:page", 1, source()); publish(store, "owner:page", 2);
        assertThat(published("owner:page")).isEmpty();
        restore(store, "owner:page", 2);
        assertThat(published("owner:page").orElseThrow()).contains("Custom title");
        restore(store, "owner:page", 3);
        assertThat(published("owner:page")).isEmpty();
    }
    @Test @DisplayName("并发保存拒绝覆盖，发布内容可在重新打开后恢复")
    void concurrentSaveDoesNotOverwriteAndPublicationSurvivesReopen() {
        var store = store(); var source = source(); component(source, "a"); component(source, "b");
        store.save("owner:page", 0, source); publish(store, "owner:page", 1);
        assertThatThrownBy(() -> store.save("owner:page", 0, source)).hasMessage("PAGE_REVISION_CONFLICT");
        assertThat(published("owner:page").orElseThrow()).contains("example:checklist", "\"instanceId\":\"a\"", "\"instanceId\":\"b\"");
        assertThat(store().read("owner:page").path("revision").asInt()).isEqualTo(1);
    }
    @Test @DisplayName("无效发布与不可用恢复均保留上次已发布内容")
    void invalidPublishAndUnavailableRestoreLeaveLastPublicationUntouched() {
        var store = store(); var first = source(); component(first, "a");
        store.save("owner:page", 0, first); publish(store, "owner:page", 1);
        var second = source().put("title", "second");
        store.save("owner:page", 1, second); publish(store, "owner:page", 2);
        catalog.available = false;
        assertThatThrownBy(() -> restore(store, "owner:page", 2)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        assertThat(store.read("owner:page").path("revision").asInt()).isEqualTo(2);
        assertThat(published("owner:page").orElseThrow()).contains("second");
        catalog.available = true;
        restore(store, "owner:page", 2);
        assertThat(published("owner:page").orElseThrow()).contains("example:checklist").doesNotContain("second");
    }
    @Test @DisplayName("缺席及版本不兼容的组件保留配置并阻止发布")
    void missingAndIncompatibleComponentsKeepTheirConfigurationButCannotPublish() {
        var store = store(); var source = source(); component(source, "a");
        store.save("owner:page", 0, source);
        catalog.available = false;
        var changed = source.deepCopy().put("title", "layout update");
        store.save("owner:page", 1, changed);
        assertThat(store.read("owner:page").path("draft").path("project")).isEqualTo(source.path("project"));
        assertThatThrownBy(() -> publish(store, "owner:page", 2)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        ((ObjectNode) children(changed).get(1).path("configuration")).put("foreign", true);
        assertThatThrownBy(() -> store.save("owner:page", 2, changed)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        catalog.available = true; catalog.version = 2;
        store.save("owner:page", 2, source);
        assertThatThrownBy(() -> publish(store, "owner:page", 3)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
    }
    @Test @DisplayName("拒绝脚本、非法配置及缺少必需区域的页面")
    void rejectsExecutableEngineFieldsInvalidConfigAndMissingOwnerRegion() {
        var store = store(); var source = source();
        ((ObjectNode) children(source).get(0)).put("script", "alert(1)");
        assertThatThrownBy(() -> store.save("owner:page", 0, source)).hasMessage("PAGE_DOCUMENT_INVALID");
        var absent = source(); children(absent).removeAll();
        assertThatThrownBy(() -> store.save("owner:page", 0, absent)).hasMessage("PAGE_REQUIRED_REGION");
        var config = source(); component(config, "a");
        ((ObjectNode) children(config).get(1)).set("configuration", PageDocuments.parse("{\"items\":false}"));
        assertThatThrownBy(() -> store.save("owner:page", 0, config)).hasMessage("PAGE_CONFIGURATION_INVALID");
        assertThat(Files.exists(directory.resolve("owner_page.json"))).isFalse();
    }
    @Test @DisplayName("保存失败不改写现存文件")
    void rejectedSaveDoesNotModifyExistingBytes() throws Exception {
        var store = store(); store.save("owner:page", 0, source());
        byte[] before = Files.readAllBytes(directory.resolve("owner_page.json"));
        var invalid = source(); component(invalid, "same"); component(invalid, "same");
        assertThatThrownBy(() -> store.save("owner:page", 1, invalid)).hasMessage("PAGE_INSTANCE_INVALID");
        assertThat(Files.readAllBytes(directory.resolve("owner_page.json"))).isEqualTo(before);
    }
    @Test @DisplayName("解析器拒绝重复键、超限输入及非法编码和版本")
    void boundedParserRejectsDuplicateKeysAndOversizedInput() {
        assertThatThrownBy(() -> PageDocuments.parse("{\"a\":1,\"a\":2}")).hasMessage("PAGE_DOCUMENT_INVALID");
        assertThatThrownBy(() -> PageDocuments.parse(" ".repeat(PageDocuments.MAX_BYTES + 1))).hasMessage("PAGE_DOCUMENT_BUDGET");
        assertThatThrownBy(() -> PageDocuments.parse(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}'}))
                .hasMessage("PAGE_DOCUMENT_INVALID");
        assertThatThrownBy(() -> store().save("owner:page", 0, source().put("format", "1")))
                .hasMessage("PAGE_DOCUMENT_INVALID");
    }
    @Test @DisplayName("连接要求两端契约及 Schema 一致，循环和重复边拒绝保存")
    void connectionsRequireMatchingContractsAndAcyclicGraph() {
        var source = source();
        component(source, "a"); component(source, "b"); component(source, "c");
        var links = (ArrayNode) source.path("connections");
        links.addObject().put("from", "a").put("output", "out").put("to", "b").put("input", "in");
        links.addObject().put("from", "b").put("output", "out").put("to", "c").put("input", "in");
        var store = store(); store.save("owner:page", 0, source); publish(store, "owner:page", 1);
        assertThat(published("owner:page")).isPresent();
        catalog.inputContract = "example.text/v2";
        assertThatThrownBy(() -> store.save("owner:page", 1, source)).hasMessage("PAGE_CONNECTION_INVALID");
        catalog.inputContract = "example.text/v1"; catalog.inputSchema = "{\"type\":\"boolean\"}";
        assertThatThrownBy(() -> store.save("owner:page", 1, source)).hasMessage("PAGE_CONNECTION_INVALID");
        catalog.inputSchema = "{\"type\":\"string\"}";
        links.addObject().put("from", "c").put("output", "out").put("to", "a").put("input", "in");
        assertThatThrownBy(() -> store.save("owner:page", 1, source)).hasMessage("PAGE_CONNECTION_CYCLE");
        links.remove(2); links.add(links.get(0).deepCopy());
        assertThatThrownBy(() -> store.save("owner:page", 1, source)).hasMessage("PAGE_CONNECTION_INVALID");
    }

    private static class Catalog implements PageCatalog {
        String inputContract = "example.text/v1", inputSchema = "{\"type\":\"string\"}";
        boolean available = true; int version = 1;
        List<PageRegion> regions = List.of(new PageRegion("required", "region", "", false, false));
        String project = "";
        public List<AvailableComponent> components() {
            return available ? List.of(new AvailableComponent(new PageComponent("example:checklist", version,
                    "label", "example", "/example/component.mjs", "", "{}", "{}", true,
                    List.of(new PagePort("in", inputContract, inputSchema)),
                    List.of(new PagePort("out", "example.text/v1", "{\"type\":\"string\"}")), null), "publication")) : List.of();
        }
        public List<AvailablePage> pages() { return List.of(new AvailablePage(new EditablePage("owner:page", "page", "owner", "/page", true,
                regions, project, "", List.of(), List.of()), "page-publication")); }
        public void validate(String type, String publication, int version, String json) {
            if (!PageDocuments.parse(json).path("items").isArray()) throw PageDocuments.invalid("PAGE_CONFIGURATION_INVALID");
        }
        public String migrate(String type, String publication, int version, String json) { return json; }
    }

    @Test @DisplayName("默认结构可读取并继续组合，恢复默认草稿不改写现有发布")
    void ownerPresetAndNestedRegionAreEditableWithoutOverwritingPublication() throws Exception {
        catalog.regions = List.of(new PageRegion("card", "card", "", true, false),
                new PageRegion("field", "field", "card", false, false));
        catalog.project = """
                {"format":1,"project":{"pages":[{"frames":[{"component":{"type":"wrapper","components":[
                  {"type":"page-box","role":"header","components":[]},
                  {"type":"page-region","region":"card","components":[{"type":"page-region","region":"field"}]},
                  {"type":"page-box","role":"footer","components":[]}
                ]}}]}]}}
                """;
        var store = store(); var source = store.defaults("owner:page");
        var card = (ArrayNode) children(source).get(1).path("components");
        card.addObject().put("type", "page-text").put("content", "Inside card");
        ((ArrayNode) children(source).get(2).path("components")).addObject().put("type", "page-link")
                .put("content", "Site information").put("href", "https://example.org/info");
        store.save("owner:page", 0, source); publish(store, "owner:page", 1);
        byte[] before = Files.readAllBytes(directory.resolve("owner_page.json"));
        assertThat(published("owner:page").orElseThrow()).contains("Inside card", "https://example.org/info", "footer");
        assertThat(store.defaults("owner:page").toString()).doesNotContain("Inside card");
        assertThat(Files.readAllBytes(directory.resolve("owner_page.json"))).isEqualTo(before);
        JsonNode field = card.remove(0); children(source).add(field);
        assertThatThrownBy(() -> store.save("owner:page", 1, source)).hasMessage("PAGE_REGION_PARENT");
        assertThat(Files.readAllBytes(directory.resolve("owner_page.json"))).isEqualTo(before);
    }

    @Test @DisplayName("链接拒绝可执行协议、协议相对地址和凭据，不接受页面自报区域权限")
    void linksAndRegionRulesCannotEscalateThroughDocumentFields() {
        var store = store();
        for (String url : List.of("javascript:alert(1)", "data:text/html,x", "//example.org", "https://user:pass@example.org", "/\\example.org")) {
            var source = source(); children(source).addObject().put("type", "page-link").put("content", "Link").put("href", url);
            assertThatThrownBy(() -> store.save("owner:page", 0, source)).hasMessage("PAGE_LINK_INVALID");
        }
        var source = source(); ((ObjectNode) children(source).get(0)).put("container", true);
        assertThatThrownBy(() -> store.save("owner:page", 0, source)).hasMessage("PAGE_DOCUMENT_INVALID");
        var leaf = source(); ((ObjectNode) children(leaf).get(0)).putArray("components").addObject().put("type", "page-text").put("content", "Unexpected");
        assertThatThrownBy(() -> store.save("owner:page", 0, leaf)).hasMessage("PAGE_NODE_INVALID");
    }
}
