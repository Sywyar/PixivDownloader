package top.sywyar.pixivdownload.openapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.plugin.api.web.page.EditablePage;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/** Projects the declaring controller's documentation alongside springdoc's inferred wire contract. */
@Component
final class ApiOperationDocumentation implements OperationCustomizer {
    static final String SECTION_TITLE = "x-pixiv-documentation-section-title";
    static final String EXPLICIT_ID = "x-pixiv-explicit-operation-id";
    static final String SECTION_DESCRIPTION = "x-pixiv-section-description";
    private static final String DETAILS = "x-pixiv-pending-operation-details";
    private final LocaleBundlePolicy locales;
    private final ResourceBundleMessageResolver common;
    private final ClassValue<ResourceBundleMessageResolver> bundles = new ClassValue<>() {
        @Override protected ResourceBundleMessageResolver computeValue(Class<?> controller) {
            String section = controller.getAnnotation(HttpApiGroup.class).value();
            return ResourceBundleMessageResolver.of(null, controller.getClassLoader(), locales, "i18n.http." + section);
        }
    };

    ApiOperationDocumentation(LocaleBundlePolicy locales) {
        this.locales = locales;
        this.common = ResourceBundleMessageResolver.of(null, getClass().getClassLoader(), locales, "i18n.http.common");
    }

    @Override public Operation customize(Operation operation, HandlerMethod handler) {
        var declaredOperation = handler.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        if (declaredOperation != null && !declaredOperation.operationId().isBlank()) {
            operation.addExtension(EXPLICIT_ID, declaredOperation.operationId());
        }
        Class<?> type = handler.getMethod().getDeclaringClass();
        var access = handler.getMethodAnnotation(top.sywyar.pixivdownload.plugin.api.web.HttpApiAccess.class);
        if (access == null) access = type.getAnnotation(top.sywyar.pixivdownload.plugin.api.web.HttpApiAccess.class);
        if (access != null) operation.addExtension("x-handler-access", access.value().name());
        HttpApiGroup section = type.getAnnotation(HttpApiGroup.class);
        if (section == null) {
            describeTransport(operation, handler, LocaleContextHolder.getLocale());
            return operation;
        }
        var text = bundles.get(type);
        Locale locale = LocaleContextHolder.getLocale();
        RequestMapping mapping = handler.getMethodAnnotation(RequestMapping.class);
        String verb = mapping == null || mapping.method().length != 1 ? "ANY" : mapping.method()[0].name();
        String key = type.getSimpleName() + "." + handler.getMethod().getName() + "." + verb + ".";
        operation.setSummary(text.getOrDefault(locale, key + "summary", operation.getSummary()));
        operation.setDescription(text.getOrDefault(locale, key + "description", operation.getDescription()));
        String details = text.getOrDefault(locale, key + "details", null);
        if (details != null && !details.isBlank()) operation.addExtension(DETAILS, details);
        String guide = text.getOrDefault(locale, key + "guide", text.getOrDefault(locale, "guide", null));
        if (guide != null) operation.setExternalDocs(new io.swagger.v3.oas.models.ExternalDocumentation().url(guide));
        operation.setTags(List.of(section.value()));
        var sectionDescription = text.getOrDefault(locale, "description", null);
        if (sectionDescription != null) operation.addExtension(SECTION_DESCRIPTION, sectionDescription);
        operation.addExtension(SECTION_TITLE, text.getOrDefault(locale, "title", section.value()));
        if (operation.getParameters() != null) operation.getParameters().forEach(param -> {
            String keyName = "parameter." + param.getName();
            var schema = param.getSchema();
            if (schema != null && schema.getDefault() != null && param.getExample() == null
                    && (param.getExamples() == null || param.getExamples().isEmpty())) {
                param.setExample(schema.getDefault());
            }
            String pageLabel = keyName;
            if (("page".equals(param.getName()) || "p".equals(param.getName())) && schema != null
                    && schema.getMinimum() != null && ("0".equals(schema.getMinimum().toString())
                    || "1".equals(schema.getMinimum().toString()))) {
                pageLabel = "parameter.page.first" + schema.getMinimum().toPlainString();
            }
            String standard = common.getOrDefault(locale, pageLabel, param.getDescription());
            String shared = text.getOrDefault(locale, keyName, standard);
            param.setDescription(text.getOrDefault(locale, key + "parameter." + param.getName(), shared));
        });
        var operationLabels = text.localMessages(locale, key);
        if (operation.getRequestBody() != null) {
            var body = operation.getRequestBody();
            body.setDescription(text.getOrDefault(locale, key + "body", body.getDescription()));
            var content = body.getContent();
            describeInlineFields(content, key + "body", operationLabels, locale);
            if (content != null && content.size() == 1 && content.containsKey(MediaType.APPLICATION_JSON_VALUE)
                    && Arrays.stream(handler.getMethodParameters()).anyMatch(p -> MultipartFile.class.isAssignableFrom(p.getParameterType()))) {
                content.put(MediaType.MULTIPART_FORM_DATA_VALUE, content.remove(MediaType.APPLICATION_JSON_VALUE));
            }
        }
        // springdoc infers the body for useReturnTypeSchema but drops the annotation's links.
        for (var declared : handler.getMethod().getAnnotationsByType(io.swagger.v3.oas.annotations.responses.ApiResponse.class)) {
            if (declared.links().length == 0 || operation.getResponses() == null) continue;
            var response = operation.getResponses().get(declared.responseCode());
            if (response != null) io.swagger.v3.core.util.AnnotationsUtils.getLinks(declared.links()).forEach((name, link) -> {
                link.setDescription(text.getOrDefault(locale, key + "link." + name,
                        common.getOrDefault(locale, "link." + name, link.getDescription())));
                response.addLink(name, link);
            });
        }
        if (operation.getResponses() != null) operation.getResponses().forEach((status, response) -> {
            String fallback = response.getDescription();
            if (status.startsWith("2")) {
                fallback = text.getOrDefault(locale, key + "success", fallback);
                if (status.equals("204") && operation.getResponses().containsKey("200")) {
                    fallback = common.getOrDefault(locale, "response.empty", fallback);
                }
            } else if (status.matches("[45][0-9]{2}")) {
                var code = HttpStatus.resolve(Integer.parseInt(status));
                if (fallback == null || fallback.isBlank() || fallback.equals(status)
                        || code != null && code.getReasonPhrase().equalsIgnoreCase(fallback)) {
                    fallback = common.getOrDefault(locale, "response." + status, fallback);
                }
                fallback = text.getOrDefault(locale, type.getSimpleName() + ".response." + status, fallback);
            }
            String responseKey = key + "response." + status;
            response.setDescription(text.getOrDefault(locale, responseKey, fallback));
            if ("202".equals(status) && response.getHeaders() != null) {
                var location = response.getHeaders().get("Location");
                if (location != null && (location.getDescription() == null || location.getDescription().isBlank())) {
                    location.setDescription(common.getOrDefault(locale, "header.accepted.location", null));
                }
            }
            describeInlineFields(response.getContent(), responseKey, operationLabels, locale);
        });
        describeTransport(operation, handler, locale);
        for (var owner : List.<AnnotatedElement>of(type, handler.getMethod())) {
            for (KnownErrorCodes codes : owner.getAnnotationsByType(KnownErrorCodes.class)) {
                if ("400".equals(codes.status()) && Arrays.asList(codes.value()).contains(top.sywyar.pixivdownload.GlobalExceptionHandler.INVALID_PARAMETER)) {
                    HttpBindingDocumentation.addError(operation, codes.status(),
                            "#/components/schemas/" + HttpBindingDocumentation.PARAMETER_ERROR, null);
                    if (codes.value().length == 1) continue;
                }
                var response = operation.getResponses().get(codes.status());
                if (response == null || response.getContent() == null) continue;
                var media = response.getContent().get(MediaType.APPLICATION_JSON_VALUE);
                if (media == null || media.getSchema() == null) continue;
                var known = HttpBindingDocumentation.withCodes(media.getSchema(), codes.value());
                media.setSchema(new ComposedSchema().addAnyOfItem(media.getSchema()).addAnyOfItem(known));
            }
        }
        return operation;
    }

    private void describeTransport(Operation operation, HandlerMethod handler, Locale locale) {
        HttpBindingDocumentation.describe(operation, handler, label -> common.getOrDefault(locale, label, null));
        HttpBindingDocumentation.describeFileRanges(operation, label -> common.getOrDefault(locale, label, null));
    }

    private void describeInlineFields(Content content, String key, Map<String, String> labels, Locale locale) {
        if (content == null || content.isEmpty()) return;
        if (labels.keySet().stream().noneMatch(label -> label.startsWith(key + "."))) return;
        for (var media : content.values()) {
            var schema = media.getSchema();
            if (schema == null || schema.get$ref() != null) continue;
            // Springdoc reuses property schemas, including the binary file part of unrelated uploads.
            var copy = io.swagger.v3.core.util.Json31.mapper().convertValue(schema, Schema.class);
            describeProperties(copy, key, labels::getOrDefault, locale);
            media.setSchema(copy);
        }
    }

    // Keep the DTO referenced by the media schema until springdoc has retained its components.
    static void describeEventStreams(Operation operation) {
        operation.getResponses().values().forEach(response -> {
            var stream = response.getContent() == null ? null : response.getContent().get(MediaType.TEXT_EVENT_STREAM_VALUE);
            if (stream != null && stream.getExtensions() != null
                    && stream.getExtensions().remove("x-sse-data-event") instanceof String event) {
                var events = new java.util.LinkedHashMap<String, Schema<?>>();
                events.put(event, stream.getSchema());
                if (stream.getExtensions().remove("x-sse-text-events") instanceof String names)
                    for (String name : names.split(",")) events.put(name, new io.swagger.v3.oas.models.media.StringSchema());
                stream.addExtension("x-sse-events", events);
                stream.setSchema(new io.swagger.v3.oas.models.media.StringSchema());
            }
        });
    }

    void finishDetails(Operation operation, Map<String, ObjectNode> submissions, Locale locale) {
        var paragraphs = new java.util.ArrayList<String>();
        if (operation.getExtensions() != null && operation.getExtensions().remove(DETAILS) instanceof String body)
            paragraphs.add(body);
        submissions.forEach((type, schema) -> {
            String description = schema.path("description").asText("");
            if (!description.isBlank()) paragraphs.add("#### `" + type + "`\n\n" + description);
        });
        if (paragraphs.isEmpty()) return;
        String introduction = operation.getDescription();
        String heading = common.get(locale, "operation.details");
        operation.addExtension("x-details-heading", heading);
        operation.setDescription((introduction == null || introduction.isBlank() ? "" : introduction + "\n\n")
                + "### " + heading + "\n\n" + String.join("\n\n", paragraphs));
    }

    void describeModels(OpenAPI document, Collection<HandlerMethod> handlers, Locale locale) {
        WorkTemplateDocumentation.describeCatalog(document, common, locale);
        ProgramPermissionDocumentation.describeGrammar(document, common.localMessages(locale, "permissionGrammar."));
        var schemas = document.getComponents().getSchemas();
        if (schemas == null) return;
        var editablePage = schemas.get(EditablePage.class.getCanonicalName());
        if (editablePage != null) editablePage.addRequiredItem("regions");
        var visited = new HashSet<String>();
        var labels = new HashMap<>(common.localMessages(locale, "schema."));
        for (var handler : handlers) {
            Class<?> type = handler.getMethod().getDeclaringClass();
            var section = type.getAnnotation(HttpApiGroup.class);
            if (section != null && visited.add(section.value())) {
                labels.putAll(bundles.get(type).localMessages(locale, "schema."));
            }
        }
        // A shared DTO may have different meanings across operations. Reuse only an unambiguous description.
        Map<String, String> usage = new HashMap<>();
        if (document.getPaths() != null) document.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
            var body = op.getRequestBody();
            if (body != null) recordUsage(usage, body.getContent(), body.getDescription());
            if (op.getResponses() != null) op.getResponses().forEach((status, response) -> {
                if (status.startsWith("2")) recordUsage(usage, response.getContent(), response.getDescription());
            });
        }));
        schemas.forEach((name, schema) -> {
            String key = "schema." + name;
            String title = schema.getTitle();
            if (title == null) title = name.substring(name.lastIndexOf('.') + 1);
            schema.setTitle(labels.getOrDefault(key + ".title", title));
            String description = labels.getOrDefault(key + ".description", schema.getDescription());
            if (description == null && !usage.getOrDefault(name, "").isBlank()) description = usage.get(name);
            schema.setDescription(description);
            describeProperties(schema, key, labels::getOrDefault, locale);
        });
    }

    private static void recordUsage(Map<String, String> usage, Content content,
                                    String description) {
        if (content == null || description == null || description.isBlank()) return;
        var json = content.get(MediaType.APPLICATION_JSON_VALUE);
        if (json == null || json.getSchema() == null) return;
        String reference = json.getSchema().get$ref();
        String prefix = "#/components/schemas/";
        if (reference != null && reference.startsWith(prefix)) {
            usage.merge(reference.substring(prefix.length()), description, (first, next) -> first.equals(next) ? first : "");
        }
    }

    private void describeProperties(Schema<?> schema, String key, BiFunction<String, String, String> label, Locale locale) {
        if (schema == null || schema.get$ref() != null) return;
        if (schema.getProperties() != null) schema.getProperties().forEach((field, property) -> {
            String propertyKey = key + ".field." + field;
            WorkTemplateDocumentation.describeField(property, label.apply(propertyKey, property.getDescription()), common, locale);
            describeProperties(property, propertyKey, label, locale);
        });
        if (schema.getItems() != null) describeProperties(schema.getItems(), key + ".items", label, locale);
        if (schema.getAdditionalProperties() instanceof Schema<?> value) {
            describeProperties(value, key + ".additionalProperties", label, locale);
        }
    }
}
