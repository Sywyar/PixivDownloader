package top.sywyar.pixivdownload.pageeditor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;

/** 文档和 CSS 声明输入共用同一受控样式边界。 */
final class PageStyles {
    private static final Set<String> PROPERTIES = Set.of("display", "flex-direction", "flex-wrap", "justify-content", "align-items", "gap",
            "padding", "padding-top", "padding-bottom", "padding-left", "padding-right", "margin", "margin-top", "margin-bottom", "margin-left", "margin-right",
            "width", "max-width", "min-width", "min-height", "font-size", "font-weight", "line-height", "text-align", "color",
            "background-color", "border", "border-color", "border-width", "border-style", "border-radius", "box-shadow", "grid-template-columns", "flex", "order");

    static java.util.List<String> properties() { return PROPERTIES.stream().sorted().toList(); }

    static ObjectNode validate(JsonNode source) {
        ObjectNode result = PageDocuments.JSON.createObjectNode();
        if (source.isMissingNode()) return result;
        if (!source.isObject()) throw PageDocuments.invalid("PAGE_STYLE_INVALID");
        source.fields().forEachRemaining(field -> {
            String value = field.getValue().asText();
            if (!PROPERTIES.contains(field.getKey()) || !field.getValue().isTextual() || value.length() > 128
                    || !value.matches("[a-zA-Z0-9 #.,()%_-]+") || value.toLowerCase(Locale.ROOT).contains("url")
                    || value.toLowerCase(Locale.ROOT).contains("expression") || value.strip().equalsIgnoreCase("none") || value.contains("-999"))
                throw PageDocuments.invalid("PAGE_STYLE_INVALID");
            result.put(field.getKey(), value);
        });
        return result;
    }

    static ObjectNode parse(String css) {
        if (css.length() > PageDocuments.MAX_BYTES) throw PageDocuments.invalid("PAGE_DOCUMENT_BUDGET");
        ObjectNode declarations = PageDocuments.JSON.createObjectNode();
        // 只接受属性声明；不借浏览器容错静默丢弃选择器、注释或无效片段。
        for (String declaration : css.split(";")) {
            if (declaration.isBlank()) continue;
            int colon = declaration.indexOf(':');
            if (colon < 1) throw PageDocuments.invalid("PAGE_STYLE_INVALID");
            String name = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).strip();
            ObjectNode item = PageDocuments.JSON.createObjectNode().put(name, value);
            validate(item);
            declarations.put(name, value);
        }
        return declarations;
    }
}
