package top.sywyar.pixivdownload.pageeditor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** 文档和 CSS 声明输入共用同一受控样式边界。 */
final class PageStyles {
    static java.util.List<String> properties() { return top.sywyar.pixivdownload.plugin.api.web.page.PagePresentation.properties(); }

    static ObjectNode validate(JsonNode source) {
        ObjectNode result = PageDocuments.JSON.createObjectNode();
        if (source.isMissingNode()) return result;
        if (!source.isObject()) throw PageDocuments.invalid("PAGE_STYLE_INVALID");
        source.properties().iterator().forEachRemaining(field -> {
            String value = field.getValue().asString();
            if (!field.getValue().isString()) throw PageDocuments.invalid("PAGE_STYLE_INVALID");
            top.sywyar.pixivdownload.plugin.api.web.page.PagePresentation.style(field.getKey(), value);
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
