package top.sywyar.pixivdownload.pageeditor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("页面绑定依据 owner 作用域和协议，不推测 DOM 表单")
class PageBindingTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @org.junit.jupiter.api.AfterEach
    void cleanup() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }
    final String schema = "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
    PageInteraction.Target target(String id, String region) {
        return new PageInteraction.Target(id, id, "form:submit", region, schema, List.of(OperationVerification.INTERACTION), 1);
    }
    PageDocuments documents(List<PageInteraction.Target> targets) {
        return new PageDocuments(new PageCatalog() {
            public List<AvailablePage> pages() {
                return List.of(new AvailablePage(new EditablePage("form:page", "page", "form", "/form", true,
                        List.of(new PageRegion("first", "first", "", true, false), new PageRegion("second", "second", "", true, false)),
                        "", "", List.of(), targets), "page"));
            }
            public List<AvailableComponent> components() {
                return List.of(new AvailableComponent(new PageComponent("proof:widget", 1, "widget", "proof", "/widget.mjs", "",
                        schema, "{}", true, List.of(), List.of(), new PageInteraction.Participant("proof", OperationVerification.INTERACTION)), "component"),
                        new AvailableComponent(new PageComponent("proof:plain", 1, "plain", "proof", "/plain.mjs", "",
                                schema, "{}", true, List.of(), List.of(), null), "plain"));
            }
            public void validate(String type, String publication, int version, String configuration) { }
            public String migrate(String type, String publication, int version, String configuration) { throw new AssertionError(); }
        });
    }
    ArrayNode regions(JsonNode document) { return (ArrayNode) document.at("/project/pages/0/frames/0/component/components"); }
    ObjectNode add(JsonNode document, int region, String instance) {
        return ((ArrayNode) regions(document).get(region).path("components")).addObject().put("type", "page-component")
                .put("componentType", "proof:widget").put("instanceId", instance).put("configVersion", 1)
                .set("configuration", PageDocuments.parse("{}"));
    }

    @Test @DisplayName("唯一目标在保存时生成显式绑定，多个表单保持实例隔离")
    void uniqueTargetsAreExplicitAndScoped() {
        var documents = documents(List.of(target("a", "first"), target("b", "second")));
        var source = documents.initial("form:page");
        add(source, 0, "one"); add(source, 1, "two");
        var normalized = documents.normalize("form:page", source, source);
        assertThat(regions(normalized).get(0).at("/components/0/target").asText()).isEqualTo("a");
        assertThat(regions(normalized).get(1).at("/components/0/target").asText()).isEqualTo("b");
        assertThat(documents.validate("form:page", normalized, normalized, true).toString()).contains("\"bindingStatus\":\"bound\"");
        ObjectNode moved = ((ObjectNode) ((ArrayNode) regions(normalized).get(0).path("components")).remove(0)).deepCopy();
        ((ArrayNode) regions(normalized).get(1).path("components")).add(moved);
        assertThatThrownBy(() -> documents.validate("form:page", normalized, source, true)).hasMessage("PAGE_BINDING_INVALID");
    }

    @Test @DisplayName("未声明动作的表单通过关系配置绑定，多个提交动作必须选择且不能重定向 owner 目标")
    void configuredActions() {
        var documents = documents(List.of());
        var source = documents.initial("form:page");
        var relations = source.putArray("actions");
        relations.addObject().put("id", "save").put("formId", "editor").put("actionId", "save-button")
                .put("region", "first").put("operation", "").put("endpoint", "other:http-save");
        relations.addObject().put("id", "publish").put("formId", "editor").put("actionId", "publish-button")
                .put("region", "first").put("operation", "").put("endpoint", "other:http-publish");
        relations.addObject().put("id", "secondary").put("formId", "second-form").put("actionId", "")
                .put("region", "second").put("operation", "other:shared").put("endpoint", "");
        var component = add(source, 0, "proof");
        assertThatThrownBy(() -> documents.validate("form:page", source, source, true)).hasMessage("PAGE_BINDING_AMBIGUOUS");
        component.put("target", "publish");
        add(source, 1, "second-proof");
        var normalized = documents.normalize("form:page", source, source);
        assertThat(regions(normalized).get(1).at("/components/0/target").asText()).isEqualTo("secondary");
        var view = documents.validate("form:page", normalized, normalized, true);
        assertThat(view.path("targets")).hasSize(3);
        assertThat(view.path("actions")).isEqualTo(relations);
        var declared = documents(List.of(target("save", "first")));
        assertThatThrownBy(() -> declared.validate("form:page", source, source, false)).hasMessage("PAGE_ACTION_FORBIDDEN");
        ((ObjectNode) relations.get(1)).put("actionId", "save-button");
        assertThatThrownBy(() -> documents.validate("form:page", source, source, false)).hasMessage("PAGE_ACTION_INVALID");
    }

    @Test @DisplayName("歧义需要选择，无匹配可保留草稿但不能发布保护")
    void ambiguityAndNoMatch() {
        var documents = documents(List.of(target("a", "first"), target("b", "first")));
        var source = documents.initial("form:page");
        ObjectNode component = add(source, 0, "one");
        assertThat(documents.validate("form:page", source, source, false).toString()).contains("ambiguous");
        assertThatThrownBy(() -> documents.validate("form:page", source, source, true)).hasMessage("PAGE_BINDING_AMBIGUOUS");
        component.put("target", "b");
        assertThat(documents.validate("form:page", source, source, true).toString()).contains("\"target\":\"b\"");
        var unmatched = documents.initial("form:page"); add(unmatched, 1, "one");
        documents.normalize("form:page", unmatched, unmatched);
        assertThatThrownBy(() -> documents.validate("form:page", unmatched, unmatched, true)).hasMessage("PAGE_BINDING_UNMATCHED");
    }

    @Test @DisplayName("保存绑定只修改组件节点，业务配置中的同名字段原样保留")
    void savesBindingWithoutChangingBusinessObjects() {
        var documents = documents(List.of(target("a", "first")));
        var source = documents.initial("form:page");
        var component = add(source, 0, "one");
        var configuration = PageDocuments.parse("""
                {"instanceId":"one","target":"business-destination",
                 "components":[{"type":"page-component","instanceId":"one","target":"nested-destination"}],
                 "nested":{"instanceId":"one","target":"other-destination"}}
                """);
        component.set("configuration", configuration.deepCopy());
        var plain = add(source, 1, "plain").put("componentType", "proof:plain");
        var plainConfiguration = PageDocuments.parse("{\"instanceId\":\"plain\",\"target\":\"business\"}");
        plain.set("configuration", plainConfiguration.deepCopy());
        var store = new PageStore(directory, documents, new PageStoreTest.TestPublications());
        var saved = store.save("form:page", 0, source).path("draft");
        var actual = regions(saved).get(0).at("/components/0");
        assertThat(actual.path("target").asText()).isEqualTo("a");
        assertThat(actual.path("configuration")).isEqualTo(configuration);
        assertThat(regions(store.read("form:page").path("draft")).get(0).at("/components/0/configuration"))
                .isEqualTo(configuration);
        assertThat(component.has("target")).isFalse();
        assertThat(regions(saved).get(1).at("/components/0").has("target")).isFalse();
        assertThat(regions(saved).get(1).at("/components/0/configuration")).isEqualTo(plainConfiguration);
    }

    @Test @DisplayName("复制不能复用实例身份或超过默认参与数量，运行证明不能进入存档")
    void rejectsCopiesAndProofFields() {
        var documents = documents(List.of(target("a", "first")));
        var source = documents.initial("form:page");
        var component = add(source, 0, "same"); add(source, 0, "same");
        assertThatThrownBy(() -> documents.validate("form:page", source, source, true)).hasMessage("PAGE_INSTANCE_INVALID");
        ((ObjectNode) regions(source).get(0).at("/components/1")).put("instanceId", "copy");
        assertThatThrownBy(() -> documents.validate("form:page", source, source, true)).hasMessage("PAGE_BINDING_CAPACITY");
        ((ArrayNode) regions(source).get(0).path("components")).remove(1);
        component.put("proof", "must-not-persist");
        assertThatThrownBy(() -> documents.normalize("form:page", source, source)).hasMessage("PAGE_DOCUMENT_INVALID");
    }
}
