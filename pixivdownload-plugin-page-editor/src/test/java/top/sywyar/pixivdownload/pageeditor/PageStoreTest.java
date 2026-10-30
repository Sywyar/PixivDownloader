package top.sywyar.pixivdownload.pageeditor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PageStoreTest {
    @TempDir Path directory;
    private final Catalog catalog = new Catalog();
    private PageStore store() { return new PageStore(directory, new PageDocuments(catalog)); }
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
        assertThat(store.published("owner:page")).isEmpty();
        assertThat(Files.exists(directory.resolve("owner_page.json"))).isFalse();
        var source = source();
        var root = (ObjectNode) source.at("/project/pages/0/frames/0/component");
        root.putObject("attributes").put("id", "generated-wrapper");
        var rule = ((ObjectNode) source.path("project")).putArray("styles").addObject().put("mediaText", "");
        rule.putArray("selectors").add("#generated-wrapper");
        rule.putObject("style").put("display", "flex");
        ((ObjectNode) children(source).get(0)).putObject("attributes").put("id", "generated-region");
        store.save("owner:page", 0, source);
        assertThat(store.published("owner:page")).isEmpty();
        store.publish("owner:page", 1);
        byte[] saved = Files.readAllBytes(directory.resolve("owner_page.json"));
        assertThat(store().published("owner:page")).isEmpty();
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
            store.save("owner:page", revision++, source); store.publish("owner:page", revision++);
            assertThat(store.published("owner:page")).isPresent();
        }
    }
    @Test @DisplayName("默认布局与自定义发布之间可恢复，保留两版源文档")
    void defaultPublicationCanRestorePreviousCustomizationAndReturnToDefault() {
        var store = store();
        store.save("owner:page", 0, source().put("title", "Custom title")); store.publish("owner:page", 1);
        store.save("owner:page", 2, source()); store.publish("owner:page", 3);
        assertThat(store.published("owner:page")).isEmpty();
        store.restore("owner:page", 4);
        assertThat(store.published("owner:page").orElseThrow()).contains("Custom title");
        store.restore("owner:page", 5);
        assertThat(store.published("owner:page")).isEmpty();
    }
    @Test @DisplayName("并发保存拒绝覆盖，发布内容可在重新打开后恢复")
    void concurrentSaveDoesNotOverwriteAndPublicationSurvivesReopen() {
        var store = store(); var source = source(); component(source, "a"); component(source, "b");
        store.save("owner:page", 0, source); store.publish("owner:page", 1);
        assertThatThrownBy(() -> store.save("owner:page", 0, source)).hasMessage("PAGE_REVISION_CONFLICT");
        assertThat(store().published("owner:page").orElseThrow()).contains("example:checklist", "\"instanceId\":\"a\"", "\"instanceId\":\"b\"");
        assertThat(store().read("owner:page").path("revision").asInt()).isEqualTo(2);
    }
    @Test @DisplayName("无效发布与不可用恢复均保留上次已发布内容")
    void invalidPublishAndUnavailableRestoreLeaveLastPublicationUntouched() {
        var store = store(); var first = source(); component(first, "a");
        store.save("owner:page", 0, first); store.publish("owner:page", 1);
        var second = source().put("title", "second");
        store.save("owner:page", 2, second); store.publish("owner:page", 3);
        catalog.available = false;
        assertThatThrownBy(() -> store.restore("owner:page", 4)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        assertThat(store.read("owner:page").path("revision").asInt()).isEqualTo(4);
        assertThat(store.published("owner:page").orElseThrow()).contains("second");
        catalog.available = true;
        store.restore("owner:page", 4);
        assertThat(store.published("owner:page").orElseThrow()).contains("example:checklist").doesNotContain("second");
    }
    @Test @DisplayName("缺席及版本不兼容的组件保留配置并阻止发布")
    void missingAndIncompatibleComponentsKeepTheirConfigurationButCannotPublish() {
        var store = store(); var source = source(); component(source, "a");
        store.save("owner:page", 0, source);
        catalog.available = false;
        var changed = source.deepCopy().put("title", "layout update");
        store.save("owner:page", 1, changed);
        assertThat(store.read("owner:page").path("draft").path("project")).isEqualTo(source.path("project"));
        assertThatThrownBy(() -> store.publish("owner:page", 2)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        ((ObjectNode) children(changed).get(1).path("configuration")).put("foreign", true);
        assertThatThrownBy(() -> store.save("owner:page", 2, changed)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        catalog.available = true; catalog.version = 2;
        store.save("owner:page", 2, source);
        assertThatThrownBy(() -> store.publish("owner:page", 3)).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
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
        var store = store(); store.save("owner:page", 0, source); store.publish("owner:page", 1);
        assertThat(store.published("owner:page")).isPresent();
        catalog.inputContract = "example.text/v2";
        assertThatThrownBy(() -> store.save("owner:page", 2, source)).hasMessage("PAGE_CONNECTION_INVALID");
        catalog.inputContract = "example.text/v1"; catalog.inputSchema = "{\"type\":\"boolean\"}";
        assertThatThrownBy(() -> store.save("owner:page", 2, source)).hasMessage("PAGE_CONNECTION_INVALID");
        catalog.inputSchema = "{\"type\":\"string\"}";
        links.addObject().put("from", "c").put("output", "out").put("to", "a").put("input", "in");
        assertThatThrownBy(() -> store.save("owner:page", 2, source)).hasMessage("PAGE_CONNECTION_CYCLE");
        links.remove(2); links.add(links.get(0).deepCopy());
        assertThatThrownBy(() -> store.save("owner:page", 2, source)).hasMessage("PAGE_CONNECTION_INVALID");
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
                    List.of(new PagePort("out", "example.text/v1", "{\"type\":\"string\"}"))), "publication")) : List.of();
        }
        public List<AvailablePage> pages() { return List.of(new AvailablePage(new EditablePage("owner:page", "page", "owner", "/page", true,
                regions, project, "", List.of()), "page-publication")); }
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
        store.save("owner:page", 0, source); store.publish("owner:page", 1);
        byte[] before = Files.readAllBytes(directory.resolve("owner_page.json"));
        assertThat(store.published("owner:page").orElseThrow()).contains("Inside card", "https://example.org/info", "footer");
        assertThat(store.defaults("owner:page").toString()).doesNotContain("Inside card");
        assertThat(Files.readAllBytes(directory.resolve("owner_page.json"))).isEqualTo(before);
        JsonNode field = card.remove(0); children(source).add(field);
        assertThatThrownBy(() -> store.save("owner:page", 2, source)).hasMessage("PAGE_REGION_PARENT");
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
