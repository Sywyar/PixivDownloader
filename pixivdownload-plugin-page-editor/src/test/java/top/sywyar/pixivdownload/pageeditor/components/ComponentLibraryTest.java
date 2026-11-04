package top.sywyar.pixivdownload.pageeditor.components;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ComponentLibraryTest {
    @TempDir Path directory;
    @AfterEach
    void cleanup() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    private ObjectNode source(String text) {
        var source = ComponentLibrary.JSON.createObjectNode();
        source.put("name", "Footer").put("html", "<p>" + text + "</p>").put("css", "p { padding: 8px; }")
                .put("javascript", "root.dataset.ready = 'true';").putObject("properties").putObject("title")
                .put("type", "string").put("label", "Title").put("default", "Hello");
        return source;
    }

    @Test @DisplayName("组件草稿重启后恢复，发布版本固定且不会随草稿更新")
    void savedDraftSurvivesRestartAndPublishedVersionsAreImmutable() {
        var library = new ComponentLibrary(directory);
        var created = library.save("", 0, source("first"));
        String id = created.path("id").asText();
        assertThat(new ComponentLibrary(directory).read(id).path("draft").path("html").asText()).contains("first");
        var published = library.publish(id, created.path("revision").asLong());
        String first = published.path("latest").asText();
        var edited = library.save(id, published.path("revision").asLong(), source("second"));
        assertThat(library.published(id, first).path("html").asText()).contains("first");
        var next = library.publish(id, edited.path("revision").asLong());
        assertThat(next.path("latest").asText()).isNotEqualTo(first);
        var restarted = new ComponentLibrary(directory);
        assertThat(restarted.published(id, first).path("html").asText()).contains("first");
        assertThat(restarted.published(id, next.path("latest").asText()).path("html").asText()).contains("second");
        assertThat(restarted.catalog().get(0).has("draft")).isFalse();
    }

    @Test @DisplayName("并发与非法保存不覆盖原始源码，草稿不能作为公开版本读取")
    void rejectsStaleWritesInvalidSourceAndDraftReads() throws Exception {
        var library = new ComponentLibrary(directory);
        var created = library.save("", 0, source("secret draft"));
        String id = created.path("id").asText();
        byte[] original = Files.readAllBytes(directory.resolve(id + ".json"));
        assertThatThrownBy(() -> library.save(id, 0, source("wrong"))).hasMessage("PAGE_REVISION_CONFLICT");
        assertThatThrownBy(() -> library.save(id, 1, source("wrong").put("executeOnServer", true)))
                .hasMessage("PAGE_COMPONENT_SOURCE_INVALID");
        assertThat(Files.readAllBytes(directory.resolve(id + ".json"))).isEqualTo(original);
        assertThatThrownBy(() -> library.published(id, "0".repeat(64))).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        assertThatThrownBy(() -> library.read("../escape")).hasMessage("PAGE_COMPONENT_ID_INVALID");
    }

    @Test @DisplayName("撤回保留全部版本与源码，重新启用恢复固定版本")
    void withdrawalRetainsVersionsAndConfiguration() {
        var library = new ComponentLibrary(directory);
        var state = library.save("", 0, source("saved"));
        String id = state.path("id").asText();
        state = library.publish(id, state.path("revision").asLong());
        String version = state.path("latest").asText();
        state = library.availability(id, state.path("revision").asLong(), false);
        assertThatThrownBy(() -> library.published(id, version)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        assertThat(library.version(id, version).path("html").asText()).contains("saved");
        library.availability(id, state.path("revision").asLong(), true);
        assertThat(library.published(id, version).path("html").asText()).contains("saved");
    }

    @Test @DisplayName("实例只接受已声明的纯值属性，损坏版本和超限输入失败关闭")
    void validatesPropertiesAndDetectsCorruptVersion() throws Exception {
        var library = new ComponentLibrary(directory);
        var state = library.save("", 0, source("saved"));
        String id = state.path("id").asText();
        state = library.publish(id, state.path("revision").asLong());
        String version = state.path("latest").asText();
        var config = ComponentLibrary.JSON.createObjectNode().put("componentId", id).put("version", version);
        config.putObject("properties").put("title", "Custom");
        library.validateConfiguration(config, true);
        config.withObject("/properties").put("unknown", true);
        assertThatThrownBy(() -> library.validateConfiguration(config, true)).hasMessage("PAGE_COMPONENT_PROPERTIES_INVALID");
        assertThatThrownBy(() -> library.save("", 0, source("x").put("html", "x".repeat(ComponentLibrary.MAX_SOURCE_BYTES))))
                .hasMessage("PAGE_COMPONENT_BUDGET");
        Files.writeString(directory.resolve("versions").resolve(id).resolve(version + ".json"), "{}");
        assertThatThrownBy(() -> library.published(id, version)).hasMessage("PAGE_COMPONENT_STORAGE_FAILED");
        assertThat(new ComponentLibrary(directory).catalog()).singleElement().satisfies(item -> {
            assertThat(item.path("unavailable").asBoolean()).isTrue();
            assertThat(item.path("enabled").asBoolean()).isFalse();
        });
    }

    @Test @DisplayName("布尔和数值属性使用纯文本存档并按声明类型再次校验")
    void encodedScalarsUseTheDeclaredType() {
        var library = new ComponentLibrary(directory);
        var source = source("typed");
        source.withObject("/properties").putObject("active").put("type", "boolean").put("label", "Active").put("default", true);
        source.withObject("/properties").putObject("amount").put("type", "number").put("label", "Amount").put("default", 1.5);
        var saved = library.save("", 0, source);
        var state = library.publish(saved.path("id").asText(), saved.path("revision").asLong());
        var config = ComponentLibrary.JSON.createObjectNode().put("componentId", state.path("id").asText()).put("version", state.path("latest").asText());
        config.putObject("properties").put("active", "false").put("amount", "2.75");
        library.validateConfiguration(config, true);
        for (String invalid : java.util.List.of(".5", "00", "", "NaN", "Infinity", "1e309")) {
            config.withObject("/properties").put("amount", invalid);
            assertThatThrownBy(() -> library.validateConfiguration(config, true)).hasMessage("PAGE_COMPONENT_PROPERTIES_INVALID");
        }
        config.withObject("/properties").put("amount", "2.75");
        config.withObject("/properties").put("active", "1");
        assertThatThrownBy(() -> library.validateConfiguration(config, true)).hasMessage("PAGE_COMPONENT_PROPERTIES_INVALID");
    }

    @Test @DisplayName("发布落盘失败不改动草稿状态和既有版本")
    void publicationFailurePreservesState() throws Exception {
        var library = new ComponentLibrary(directory);
        var saved = library.save("", 0, source("before"));
        String id = saved.path("id").asText();
        byte[] original = Files.readAllBytes(directory.resolve(id + ".json"));
        Files.writeString(directory.resolve("versions"), "occupied", java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> library.publish(id, saved.path("revision").asLong())).hasMessage("PAGE_COMPONENT_STORAGE_FAILED");
        assertThat(Files.readAllBytes(directory.resolve(id + ".json"))).isEqualTo(original);
        assertThat(library.catalog().get(0).path("latest").asText()).isEmpty();
    }

    @Test @DisplayName("满库重启后先修改已有组件再列出目录不会重复计数")
    void fullLibrarySupportsDirectMutationAfterRestart() throws Exception {
        var library = new ComponentLibrary(directory);
        var state = library.save("", 0, source("first"));
        for (int i = 1; i < 128; i++) library.save("", 0, source("item"));
        // 先操作目录迭代的最后一项，暴露未加载缓存与磁盘条目重复计数。
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) state = library.read(file.getFileName().toString().replace(".json", ""));
        }
        var restarted = new ComponentLibrary(directory);
        restarted.availability(state.path("id").asText(), state.path("revision").asLong(), false);
        assertThat(restarted.catalog()).hasSize(128);
        assertThatThrownBy(() -> restarted.save("", 0, source("extra"))).hasMessage("PAGE_COMPONENT_BUDGET");
    }
}
