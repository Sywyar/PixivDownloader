package top.sywyar.pixivdownload.plugin.web.page;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePresentation;
import java.util.*;

/** 正式投影独立校验呈现与 DAG；不依赖编辑器是否存在或是否已校验草稿。 */
final class PageProjection {
    private static final Set<String> TYPES = Set.of("wrapper", "page-box", "page-text", "page-link", "page-region", "page-component");
    private static final Set<String> FIELDS = Set.of("type", "id", "style", "role", "content", "href", "region", "container",
            "labelKey", "previewHidden", "children", "componentType", "instanceId", "configVersion", "configuration",
            "moduleUrl", "namespace", "inputs", "outputs", "publication", "unavailable", "interaction", "bindingStatus", "target", "policy", "operation", "componentContract");
    private final Set<String> ids = new HashSet<>();
    private final Map<String, JsonNode> instances = new HashMap<>();
    private int count;

    static void validate(JsonNode view) {
        fields(view, Set.of("format", "pageId", "title", "namespace", "publication", "revision", "previewModuleUrl",
                "stylesheets", "targets", "body", "styles", "connections", "defaultLayout", "ownerContract"));
        if (!view.path("format").isIntegralNumber() || view.path("format").asInt() != 1
                || !view.path("title").isTextual() || view.path("title").textValue().length() > 256)
            invalid("PAGE_DOCUMENT_INVALID");
        var check = new PageProjection();
        check.node(view.path("body"), 0);
        var styles = view.path("styles");
        if (!styles.isMissingNode() && (!styles.isArray() || styles.size() > 512)) invalid("PAGE_STYLE_INVALID");
        for (var rule : styles) {
            fields(rule, Set.of("media", "ids", "style"));
            if (!rule.path("media").isTextual()) invalid("PAGE_STYLE_INVALID");
            String media = rule.path("media").asText();
            if (!media.isEmpty() && !media.matches("\\(max-width: [0-9]+(?:\\.[0-9]+)?(?:px|em)\\)")) invalid("PAGE_STYLE_INVALID");
            if (!rule.path("ids").isArray() || rule.path("ids").isEmpty()) invalid("PAGE_STYLE_INVALID");
            for (var id : rule.path("ids")) if (!id.isTextual() || !check.ids.contains(id.asText())) invalid("PAGE_STYLE_INVALID");
            style(rule.path("style"));
        }
        check.connections(view.path("connections"));
    }

    private void node(JsonNode node, int depth) {
        fields(node, FIELDS);
        if (++count > 512 || depth > 24) invalid("PAGE_DOCUMENT_BUDGET");
        String type = node.path("type").asText();
        if (!TYPES.contains(type) || (depth == 0) != type.equals("wrapper")) invalid("PAGE_NODE_INVALID");
        String id = node.path("id").asText();
        if (!id.isEmpty() && (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !ids.add(id))) invalid("PAGE_INSTANCE_INVALID");
        style(node.path("style"));
        boolean container = type.equals("wrapper") || type.equals("page-box") || type.equals("page-region") && node.path("container").asBoolean();
        if (type.equals("page-box") && !Set.of("section", "header", "main", "footer").contains(node.path("role").asText())) invalid("PAGE_NODE_INVALID");
        if (type.equals("page-text") || type.equals("page-link")) {
            if (!node.path("content").isTextual() || node.path("content").asText().contains("<") || node.path("content").asText().contains(">")) invalid("PAGE_TEXT_INVALID");
            if (type.equals("page-link")) PagePresentation.link(node.path("href").asText());
        }
        if (type.equals("page-component")) instances.put(node.path("instanceId").asText(), node);
        var children = node.path("children");
        if (!children.isMissingNode() && !children.isArray() || !container && !children.isEmpty()) invalid("PAGE_NODE_INVALID");
        for (var child : children) node(child, depth + 1);
    }

    private void connections(JsonNode links) {
        if (!links.isMissingNode() && (!links.isArray() || links.size() > 256)) invalid("PAGE_CONNECTION_INVALID");
        Map<String, Set<String>> edges = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        instances.keySet().forEach(id -> incoming.put(id, 0));
        for (var link : links) {
            fields(link, Set.of("from", "to", "input", "output"));
            String from = link.path("from").asText(), to = link.path("to").asText();
            var output = port(instances.get(from), "outputs", link.path("output").asText());
            var input = port(instances.get(to), "inputs", link.path("input").asText());
            if (!output.path("contractId").equals(input.path("contractId"))
                    || !schema(output.path("schema").asText()).equals(schema(input.path("schema").asText()))) invalid("PAGE_CONNECTION_INVALID");
            if (edges.computeIfAbsent(from, ignored -> new HashSet<>()).add(to)) incoming.merge(to, 1, Integer::sum);
        }
        var ready = new ArrayDeque<String>();
        incoming.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst(); visited++;
            for (String next : edges.getOrDefault(id, Set.of())) if (incoming.compute(next, (key, count) -> count - 1) == 0) ready.add(next);
        }
        if (visited != instances.size()) invalid("PAGE_CONNECTION_CYCLE");
    }

    private static JsonNode port(JsonNode node, String direction, String name) {
        if (node != null) for (var port : node.path(direction)) if (port.path("name").asText().equals(name)) return port;
        throw new IllegalArgumentException("PAGE_CONNECTION_INVALID");
    }
    private static JsonNode schema(String value) {
        try { return WebPublicationStore.JSON.readTree(value); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("PAGE_CONNECTION_INVALID"); }
    }
    private static void style(JsonNode value) {
        if (value.isMissingNode()) return;
        if (!value.isObject()) invalid("PAGE_STYLE_INVALID");
        value.fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) invalid("PAGE_STYLE_INVALID");
            PagePresentation.style(field.getKey(), field.getValue().asText());
        });
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) invalid("PAGE_DOCUMENT_INVALID");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) invalid("PAGE_DOCUMENT_INVALID"); });
    }
    private static void invalid(String code) { throw new IllegalArgumentException(code); }
}
