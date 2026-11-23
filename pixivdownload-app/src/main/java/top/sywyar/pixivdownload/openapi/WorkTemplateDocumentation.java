package top.sywyar.pixivdownload.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.tags.Tag;
import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;
import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables.Variable;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Publishes the tokens accepted by each work template parser. */
final class WorkTemplateDocumentation {
    static final String CATALOG_TAG = "work-template-variables";
    private static final Map<String, List<String>> SCOPES;

    static {
        var scopes = new LinkedHashMap<String, List<String>>();
        scopes.put("collectionRoot", List.of(WorkTemplateVariables.COLLECTION_ROOT_PLACEHOLDER));
        scopes.put("directory", tokens(Variable::directory));
        scopes.put("download", tokens(Variable::download));
        scopes.put("import", tokens(Variable::importing));
        SCOPES = Collections.unmodifiableMap(scopes);
    }

    private WorkTemplateDocumentation() {}

    private static List<String> tokens(Predicate<Variable> applies) {
        return WorkTemplateVariables.all().stream().filter(applies).map(v -> "{" + v.token() + "}").toList();
    }

    static void describeCatalog(OpenAPI document, ResourceBundleMessageResolver messages, Locale locale) {
        document.addExtension("x-work-template-variables", SCOPES);
        var guide = new StringBuilder(messages.get(locale, "template.intro"));
        SCOPES.forEach((scope, values) -> guide.append("\n\n")
                .append(messages.getOrDefault(locale, "template.scope." + scope, scope)).append("  \n")
                .append(inlineCode(values)));
        var meanings = new LinkedHashMap<String, String>();
        for (var variable : WorkTemplateVariables.all()) {
            String token = "{" + variable.token() + "}";
            String meaning = messages.getOrDefault(locale, "template.variable." + variable.label(), token);
            meanings.put(token, meaning);
            guide.append("\n\n`").append(token).append("` — ").append(meaning);
        }
        document.addExtension("x-work-template-variable-descriptions", meanings);
        var tag = new Tag().name(CATALOG_TAG).description(guide.toString());
        tag.addExtension("x-displayName", catalogTitle(messages, locale));
        document.addTagsItem(tag);
    }

    private static String catalogTitle(ResourceBundleMessageResolver messages, Locale locale) {
        return messages.getOrDefault(locale, "template.title", "Work template variables");
    }

    static void describeField(Schema<?> property, String description, ResourceBundleMessageResolver messages, Locale locale) {
        if (description == null) return;
        for (var scope : SCOPES.entrySet()) {
            String marker = "{" + scope.getKey() + "Variables}";
            if (description.contains(marker)) {
                property.addExtension("x-work-template-scope", scope.getKey());
                description = description.replace(marker, inlineCode(scope.getValue()));
                description += "\n\n[" + catalogTitle(messages, locale) + "](#tag/" + CATALOG_TAG + ")";
                break;
            }
        }
        property.setDescription(description);
    }

    private static String inlineCode(List<String> values) {
        return values.stream().map(token -> "`" + token + "`").collect(Collectors.joining(", "));
    }
}
