package top.sywyar.pixivdownload.pageeditor;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** GrapesJS 存档归编辑器所有；正式投影仅包含校验后的布局与插件纯值配置。 */
public final class PageDocuments {
    static final int MAX_BYTES = 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> TYPES = Set.of("wrapper", "page-box", "page-text", "page-link", "page-component", "page-region");
    private static final Set<String> NODE_FIELDS = Set.of("type", "attributes", "style", "components", "content",
            "region", "componentType", "instanceId", "configVersion", "configuration", "role", "href");
    private final PageCatalog catalog;
    public PageDocuments(PageCatalog catalog) { this.catalog = catalog; }

    static JsonNode parse(String input) {
        return parse(input.getBytes(StandardCharsets.UTF_8));
    }
    static JsonNode parse(byte[] input) {
        if (input.length > MAX_BYTES) throw invalid("PAGE_DOCUMENT_BUDGET");
        try { JsonNode result = JSON.readTree(input); if (result == null) throw invalid("PAGE_DOCUMENT_INVALID"); return result; }
        catch (IOException failure) { throw invalid("PAGE_DOCUMENT_INVALID"); }
    }
    static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
    EditablePage page(String id) {
        return availablePage(id).definition();
    }
    private PageCatalog.AvailablePage availablePage(String id) {
        return catalog.pages().stream().filter(p -> p.definition().id().equals(id)).findFirst()
                .orElseThrow(() -> invalid("PAGE_OWNER_UNAVAILABLE"));
    }
    public ObjectNode initial(String id) {
        EditablePage page = page(id);
        ObjectNode doc = JSON.createObjectNode().put("format", 1).put("pageId", id).put("title", "");
        if (!page.defaultProject().isEmpty()) {
            JsonNode preset = parse(page.defaultProject());
            fields(preset, Set.of("format", "project"));
            if (!preset.path("format").isIntegralNumber() || preset.path("format").asInt() != 1) throw invalid("PAGE_DOCUMENT_INVALID");
            doc.set("project", preset.path("project").deepCopy());
        } else {
            var root = JSON.createObjectNode().put("type", "wrapper");
            root.set("components", initialRegions(page.regions(), "", 0));
            doc.putObject("project").putArray("pages").addObject().putArray("frames").addObject().set("component", root);
        }
        doc.putArray("connections"); return doc;
    }

    private ArrayNode initialRegions(List<PageRegion> regions, String parent, int depth) {
        if (depth > 24) throw invalid("PAGE_DOCUMENT_BUDGET");
        var children = JSON.createArrayNode();
        for (PageRegion region : regions) if (region.parent().equals(parent)) {
            children.addObject().put("type", "page-region").put("region", region.id())
                    .set("components", initialRegions(regions, region.id(), depth + 1));
        }
        return children;
    }

    boolean usesDefaultLayout(ObjectNode view) {
        if (!view.path("title").asText().isEmpty() || !view.path("connections").isEmpty()) return false;
        String id = view.path("pageId").asText();
        ObjectNode source = initial(id);
        ObjectNode comparison = view.deepCopy();
        removeRepeatedStyles(comparison);
        clearLayoutIds(comparison.path("body"));
        ObjectNode baseline = validate(id, source, source, true);
        removeRepeatedStyles(baseline);
        clearLayoutIds(baseline.path("body"));
        return comparison.equals(baseline);
    }

    private static void removeRepeatedStyles(ObjectNode view) {
        var nodes = new HashMap<String, JsonNode>();
        indexLayout(view.path("body"), nodes);
        var styles = (ArrayNode) view.path("styles");
        for (int index = styles.size() - 1; index >= 0; index--) {
            JsonNode rule = styles.get(index);
            if (!rule.path("media").asText().isEmpty()) continue;
            boolean repeated = true;
            for (JsonNode id : rule.path("ids")) {
                JsonNode node = nodes.get(id.asText());
                var fields = rule.path("style").fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (node == null || !field.getValue().equals(node.path("style").path(field.getKey()))) repeated = false;
                }
            }
            // GrapesJS 会同时返回节点样式及等价的 ID 规则；这不构成布局修改。
            if (repeated) styles.remove(index);
        }
    }

    private static void indexLayout(JsonNode node, Map<String, JsonNode> nodes) {
        nodes.put(node.path("id").asText(), node);
        node.path("children").forEach(child -> indexLayout(child, nodes));
    }

    private static void clearLayoutIds(JsonNode node) {
        // 编辑器自动生成的布局 ID 不构成用户定制；只比较副本，保留原始存档。
        ((ObjectNode) node).put("id", "");
        node.path("children").forEach(PageDocuments::clearLayoutIds);
    }

    public ObjectNode validate(String id, JsonNode doc, JsonNode previous, boolean publishing) {
        if (!doc.isObject() || !doc.path("format").isIntegralNumber() || doc.path("format").asInt() != 1
                || !doc.path("pageId").isTextual() || !doc.path("pageId").asText().equals(id)
                || !doc.path("title").isTextual() || doc.path("title").asText().length() > 256) throw invalid("PAGE_DOCUMENT_INVALID");
        parse(doc.toString());
        fields(doc, Set.of("format", "pageId", "title", "project", "connections"));
        fields(doc.path("project"), Set.of("pages", "styles"));
        var availablePage = availablePage(id);
        EditablePage page = availablePage.definition();
        JsonNode pages = doc.path("project").path("pages");
        if (!pages.isArray() || pages.size() != 1 || pages.get(0).path("frames").size() != 1) throw invalid("PAGE_DOCUMENT_INVALID");
        fields(pages.get(0), Set.of("frames"));
        if (!pages.get(0).path("frames").isArray()) throw invalid("PAGE_DOCUMENT_INVALID");
        fields(pages.get(0).path("frames").get(0), Set.of("component"));
        if (!pages.get(0).path("frames").get(0).path("component").path("type").asText().equals("wrapper"))
            throw invalid("PAGE_DOCUMENT_INVALID");
        var context = new Check(page, publishing, previous);
        JsonNode body = node(pages.get(0).path("frames").get(0).path("component"), context, 0, "");
        if (!context.regions.equals(context.regionDefinitions.keySet())) throw invalid("PAGE_REQUIRED_REGION");
        JsonNode rules = doc.path("project").path("styles");
        ArrayNode styles = JSON.createArrayNode();
        if (!rules.isMissingNode() && !rules.isArray()) throw invalid("PAGE_STYLE_INVALID");
        for (JsonNode rule : rules) {
            fields(rule, Set.of("selectors", "style", "mediaText"));
            String media = rule.path("mediaText").asText("");
            if (!media.isEmpty() && !media.matches("\\(max-width: [0-9]+(?:\\.[0-9]+)?(?:px|em)\\)")) throw invalid("PAGE_STYLE_INVALID");
            ArrayNode ids = JSON.createArrayNode();
            if (!rule.path("selectors").isArray()) throw invalid("PAGE_STYLE_INVALID");
            for (JsonNode selector : rule.path("selectors")) {
                if (!selector.isTextual()) {
                    fields(selector, Set.of("name", "type", "label", "active", "private", "protected"));
                    if (!selector.path("type").isIntegralNumber() || selector.path("type").asInt() != 2)
                        throw invalid("PAGE_STYLE_INVALID");
                }
                String name = selector.isTextual() ? selector.asText().replaceFirst("^#", "") : selector.path("name").asText();
                if (!context.ids.contains(name)) throw invalid("PAGE_STYLE_INVALID"); ids.add(name);
            }
            if (ids.isEmpty()) throw invalid("PAGE_STYLE_INVALID");
            var out = styles.addObject().put("media", media); out.set("ids", ids); out.set("style", PageStyles.validate(rule.path("style")));
        }
        JsonNode connections = doc.path("connections");
        if (!connections.isArray() || connections.size() > 256) throw invalid("PAGE_CONNECTION_INVALID");
        for (JsonNode link : connections) {
            fields(link, Set.of("from", "to", "input", "output"));
            var from = context.instances.get(link.path("from").asText()); var to = context.instances.get(link.path("to").asText());
            if (from == null || to == null) {
                boolean saved = previous != null && previous.path("connections").isArray()
                        && java.util.stream.StreamSupport.stream(previous.path("connections").spliterator(), false).anyMatch(link::equals);
                if (publishing || !saved) throw invalid("PAGE_CONNECTION_INVALID");
            } else {
                var output = from.outputs().stream().filter(port -> port.name().equals(link.path("output").asText())).findFirst().orElse(null);
                var input = to.inputs().stream().filter(port -> port.name().equals(link.path("input").asText())).findFirst().orElse(null);
                if (output == null || input == null || !output.contractId().equals(input.contractId())
                        || !parse(output.schema()).equals(parse(input.schema()))) throw invalid("PAGE_CONNECTION_INVALID");
            }
        }
        validateConnectionGraph(connections);
        var view = JSON.createObjectNode().put("format", 1).put("pageId", id).put("title", doc.path("title").asText());
        view.put("namespace", page.namespace()).put("publication", availablePage.publication());
        // 预览代码不进入匿名正式投影。
        if (!publishing) view.put("previewModuleUrl", page.previewModuleUrl());
        page.stylesheets().forEach(view.putArray("stylesheets")::add);
        view.set("body", body); view.set("styles", styles); view.set("connections", connections.deepCopy()); return view;
    }

    private static void validateConnectionGraph(JsonNode connections) {
        var edges = new HashMap<String, Set<String>>();
        var unique = new HashSet<String>();
        for (JsonNode link : connections) {
            String from = link.path("from").asText(), to = link.path("to").asText();
            if (!unique.add(from + "." + link.path("output").asText() + ":" + to + "." + link.path("input").asText()))
                throw invalid("PAGE_CONNECTION_INVALID");
            edges.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
        }
        for (String start : edges.keySet()) {
            var pending = new ArrayDeque<String>(edges.get(start)); var visited = new HashSet<String>();
            while (!pending.isEmpty()) {
                String next = pending.removeFirst();
                if (next.equals(start)) throw invalid("PAGE_CONNECTION_CYCLE");
                if (visited.add(next)) pending.addAll(edges.getOrDefault(next, Set.of()));
            }
        }
    }

    private final class Check {
        final EditablePage page; final boolean publishing; final JsonNode previous;
        final Set<String> ids = new HashSet<>(); final Set<String> regions = new HashSet<>();
        final Set<String> instanceIds = new HashSet<>();
        final Map<String, PageComponent> instances = new HashMap<>();
        final Map<String, PageCatalog.AvailableComponent> available = new HashMap<>();
        final Map<String, PageRegion> regionDefinitions = new HashMap<>();
        int count;
        Check(EditablePage page, boolean publishing, JsonNode previous) {
            this.page = page; this.publishing = publishing; this.previous = previous;
            catalog.components().forEach(c -> available.put(c.definition().type(), c));
            page.regions().forEach(region -> regionDefinitions.put(region.id(), region));
        }
    }

    private JsonNode node(JsonNode node, Check check, int depth, String parentRegion) {
        if (!node.isObject() || ++check.count > 512 || depth > 24) throw invalid("PAGE_DOCUMENT_BUDGET");
        fields(node, NODE_FIELDS);
        String type = node.path("type").asText();
        if (!TYPES.contains(type) || type.equals("wrapper") && depth != 0) throw invalid("PAGE_NODE_INVALID");
        if (node.has("attributes")) fields(node.get("attributes"), Set.of("id"));
        String id = node.path("attributes").path("id").asText("");
        if (!id.isEmpty() && (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !check.ids.add(id))) throw invalid("PAGE_INSTANCE_INVALID");
        var attrs = node.path("attributes").fieldNames();
        while (attrs.hasNext()) if (!attrs.next().equals("id")) throw invalid("PAGE_NODE_INVALID");
        ObjectNode out = JSON.createObjectNode().put("type", type).put("id", id);
        out.set("style", PageStyles.validate(node.path("style")));
        boolean container = type.equals("wrapper") || type.equals("page-box");
        if (type.equals("page-box")) {
            String role = node.path("role").asText("section");
            if (!Set.of("section", "header", "main", "footer").contains(role)) throw invalid("PAGE_NODE_INVALID");
            out.put("role", role);
        }
        if (type.equals("page-region")) {
            String region = node.path("region").asText();
            PageRegion definition = check.regionDefinitions.get(region);
            if (definition == null || !check.regions.add(region)) throw invalid("PAGE_REQUIRED_REGION");
            if (!definition.parent().equals(parentRegion)) throw invalid("PAGE_REGION_PARENT");
            container = definition.container(); parentRegion = region;
            out.put("region", region).put("container", container).put("labelKey", definition.labelKey())
                    .put("previewHidden", definition.previewHidden());
        } else if (type.equals("page-component")) {
            String component = node.path("componentType").asText(); String instance = node.path("instanceId").asText();
            if (!instance.matches("[A-Za-z0-9_-]{1,64}") || !check.instanceIds.add(instance)) throw invalid("PAGE_INSTANCE_INVALID");
            var available = check.available.get(component);
            JsonNode configuration = node.get("configuration");
            if (configuration == null || !node.path("configVersion").isIntegralNumber()
                    || !node.path("configVersion").canConvertToInt() || node.path("configVersion").asInt() < 1)
                throw invalid("PAGE_CONFIGURATION_INVALID");
            if (available == null || available.definition().version() != node.path("configVersion").asInt()) {
                if (check.publishing || !preserved(check.previous, instance, node)) throw invalid("PAGE_COMPONENT_UNAVAILABLE");
                out.put("unavailable", true);
            } else {
                PageComponent definition = available.definition();
                if (check.publishing && check.page.publicPage() && !definition.publicRenderable()) throw invalid("PAGE_COMPONENT_PRIVATE");
                check.instances.put(instance, definition);
                catalog.validate(component, available.publication(), node.path("configVersion").asInt(), configuration.toString());
                out.put("moduleUrl", definition.moduleUrl()).put("publication", available.publication())
                        .put("namespace", definition.namespace());
                out.set("inputs", JSON.valueToTree(definition.inputs()));
                out.set("outputs", JSON.valueToTree(definition.outputs()));
            }
            out.put("componentType", component).put("instanceId", instance).put("configVersion", node.path("configVersion").asInt());
            out.set("configuration", configuration.deepCopy());
        } else if (type.equals("page-text") || type.equals("page-link")) {
            String content = node.path("content").asText();
            if (content.contains("<") || content.contains(">")) throw invalid("PAGE_TEXT_INVALID");
            out.put("content", content);
            if (type.equals("page-link")) out.put("href", link(node.path("href").asText()));
        }
        JsonNode children = node.path("components");
        if (!children.isMissingNode() && !children.isArray()) throw invalid("PAGE_NODE_INVALID");
        if (!container && children.size() > 0) throw invalid("PAGE_NODE_INVALID");
        var result = out.putArray("children");
        for (JsonNode child : children) result.add(node(child, check, depth + 1, parentRegion));
        return out;
    }
    private static String link(String value) {
        if (value.length() > 2048 || value.isBlank() || value.contains("\\")
                || value.chars().anyMatch(c -> c <= 32 || c == 127)) throw invalid("PAGE_LINK_INVALID");
        try {
            var uri = new java.net.URI(value);
            boolean relative = value.startsWith("/") && !value.startsWith("//") && uri.getRawAuthority() == null;
            boolean external = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
            if (!relative && !external) throw invalid("PAGE_LINK_INVALID");
            return value;
        } catch (java.net.URISyntaxException failure) { throw invalid("PAGE_LINK_INVALID"); }
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw invalid("PAGE_DOCUMENT_INVALID");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid("PAGE_DOCUMENT_INVALID");
        });
    }
    private static boolean preserved(JsonNode previous, String instance, JsonNode node) {
        if (previous == null) return false;
        if (previous.isObject() && previous.path("instanceId").asText().equals(instance))
            return previous.path("componentType").equals(node.path("componentType"))
                    && previous.path("configVersion").equals(node.path("configVersion"))
                    && previous.path("configuration").equals(node.path("configuration"));
        for (JsonNode child : previous) if (preserved(child, instance, node)) return true;
        return false;
    }
}
