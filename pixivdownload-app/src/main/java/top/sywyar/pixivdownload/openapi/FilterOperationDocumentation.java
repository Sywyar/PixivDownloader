package top.sywyar.pixivdownload.openapi;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialsFilter;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.security.HookChallenges;
import top.sywyar.pixivdownload.plugin.security.HookRequestFilter;
import top.sywyar.pixivdownload.plugin.security.HttpOperationHooks;
import top.sywyar.pixivdownload.plugin.web.controller.PagePublicationFilter;

/** Describes HTTP protocols handled before MVC selects a business controller. */
@Component
final class FilterOperationDocumentation {
    private final ResourceBundleMessageResolver messages;

    FilterOperationDocumentation(LocaleBundlePolicy locales) {
        messages = ResourceBundleMessageResolver.of(null, getClass().getClassLoader(), locales, "i18n.http.core-extensions");
    }

    void describe(OpenAPI api, RouteAccessRegistry routes, Locale locale) {
        var text = messages.localMessages(locale, "filter.");
        describeParameters(api, text);
        routes.resolve(HookRequestFilter.PREPARE_PATH, HttpMethod.POST).ifPresent(owner -> {
            var input = model(api, HookRequestFilter.Preparation.class, "HookPreparation", text);
            input.setRequired(List.of("method", "url"));
            input.getProperties().get("method").setEnum(HookRequestFilter.TARGET_METHODS.stream().sorted().toList());
            input.getProperties().get("url").setMaxLength(HookRequestFilter.MAX_TARGET_URL_LENGTH);
            input.getProperties().get("token").setMaxLength(HookRequestFilter.MAX_TOKEN_LENGTH);
            input.getProperties().get("json").setAdditionalProperties(new JsonSchema());
            input.setExample(Map.of("method", "GET", "url", "/api/admin/system", "resolveOnly", true));
            var resolve = "HookTargetMatch";
            var outcome = "HookPreparationResult";
            model(api, HttpOperationHooks.TargetMatch.class, resolve, text).setRequired(List.of("code", "invoked", "endpoint"));
            model(api, HookChallenges.Response.class, outcome, text).setRequired(List.of("code", "invoked", "token", "revision", "challenges"));
            var results = new ApiResponses();
            results.addApiResponse("200", response(text, "200", new ComposedSchema()
                    .addOneOfItem(ref(resolve)).addOneOfItem(ref(outcome))));
            Map.of("400", List.of("HOOK_TARGET_INVALID"),
                    "403", List.of("HOOK_FORBIDDEN", "HOOK_REJECTED"),
                    "409", List.of("HOOK_CHALLENGE", "HOOK_STALE", "HOOK_TARGET_STALE"),
                    "413", List.of("HOOK_INPUT_TOO_LARGE"), "429", List.of("HOOK_CAPACITY"),
                    "503", List.of("HOOK_UNAVAILABLE")).forEach((status, codes) ->
                    results.addApiResponse(status, response(text, status,
                            HttpBindingDocumentation.withCodes(ref(outcome), codes.toArray(String[]::new)))));
            results.addApiResponse("401", new ApiResponse().description(text.get("filter.prepare.401")));
            var operation = new Operation().operationId("prepareHttpOperation").tags(List.of("core-extensions"))
                    .summary(text.get("filter.prepare.summary"))
                    .description(text.get("filter.prepare.description") + "\n\n" + text.get("filter.prepare.access"))
                    .requestBody(new RequestBody().required(true).description(text.get("filter.HookPreparation"))
                            .content(json(ref("HookPreparation")))).responses(results);
            operation.addSecurityItem(new SecurityRequirement());
            var schemes = api.getComponents().getSecuritySchemes();
            if (schemes != null) schemes.keySet().stream().sorted().forEach(scheme ->
                    operation.addSecurityItem(new SecurityRequirement().addList(scheme)));
            operation.addExtension("x-plugin-id", owner.pluginId());
            operation.addExtension("x-access-policy", owner.route().accessPolicy().name());
            api.getPaths().addPathItem(HookRequestFilter.PREPARE_PATH, new PathItem().post(operation));
        });
        describeCsrf(api, text);
    }

    private static void describeCsrf(OpenAPI api, Map<String, String> text) {
        HttpBindingDocumentation.fixedError(api.getComponents(), "HttpCsrfOriginError", CsrfProtectionFilter.ERROR_CODE);
        var origin = originHeader(HttpHeaders.ORIGIN, text.get("filter.csrf.origin"));
        var explicit = originHeader(HttpHeaders.ORIGIN, text.get("filter.csrf.explicit"));
        var referer = originHeader(HttpHeaders.REFERER, text.get("filter.csrf.referer"));
        String error = text.get("filter.csrf.error").replace("{code}", CsrfProtectionFilter.ERROR_CODE);
        api.getPaths().forEach((path, item) -> {
            // The filter sees concrete paths, including numeric collection IDs.
            String concretePath = UriComponentsBuilder.fromPath(path).build().expand(variable -> "1").getPath();
            item.readOperationsMap().forEach((method, operation) -> {
                if (method == PathItem.HttpMethod.TRACE || !CsrfProtectionFilter.requiresSameOriginCheck(method.name())) return;
                var ownOrigin = header(operation, HttpHeaders.ORIGIN);
                boolean explicitSignal = CsrfProtectionFilter.requiresExplicitOriginSignal(method.name(), concretePath);
                operation.addExtension("x-csrf-origin", ownOrigin.map(Parameter::getRequired).orElse(false)
                        ? "origin-required" : explicitSignal ? "origin-or-referer" : "conditional");
                if (ownOrigin.isEmpty()) {
                    operation.addParametersItem(explicitSignal ? explicit : origin);
                }
                if (!ownOrigin.map(Parameter::getRequired).orElse(false) && header(operation, HttpHeaders.REFERER).isEmpty()) {
                    operation.addParametersItem(referer);
                }
                HttpBindingDocumentation.addError(operation, "403", "#/components/schemas/HttpCsrfOriginError", error);
            });
        });
    }

    private static Optional<Parameter> header(Operation operation, String name) {
        if (operation.getParameters() == null) return Optional.empty();
        return operation.getParameters().stream()
                .filter(p -> "header".equals(p.getIn()) && name.equalsIgnoreCase(p.getName())).findFirst();
    }

    private static HeaderParameter originHeader(String name, String description) {
        var header = new HeaderParameter();
        header.setName(name);
        header.setDescription(description);
        header.setSchema(new StringSchema().format("uri"));
        header.setRequired(false);
        return header;
    }

    private void describeParameters(OpenAPI api, Map<String, String> text) {
        var selected = new StringSchema();
        selected.setPattern("^[a-zA-Z0-9-]{1,64}(,[a-zA-Z0-9-]{1,64}){0," + (AcquisitionCredentials.MAX_CANDIDATES - 1) + "}$");
        selected.setMaxLength(AcquisitionCredentialsFilter.MAX_SELECTION_LENGTH);
        String name = AcquisitionCredentialsFilter.HEADER;
        var selection = new HeaderParameter().name(name).schema(selected).description(text.get("filter." + name));
        selection.addExtension("x-program-permission", "core:credentials.use");
        api.getComponents().addParameters(name, selection);
        var credentialErrors = Map.of("400", AcquisitionCredentialsFilter.CREDENTIAL_SELECTION_REJECTED,
                "403", AcquisitionCredentialsFilter.CREDENTIAL_FORBIDDEN, "503", AcquisitionCredentialsFilter.ACQUISITION_CREDENTIALS_UNAVAILABLE);
        credentialErrors.forEach((status, code) -> {
            if (status.equals("400")) {
                String model = top.sywyar.pixivdownload.core.web.HttpParameterError.class.getCanonicalName();
                api.getComponents().addSchemas(code, HttpBindingDocumentation.withCodes(ref(model), code));
            } else {
                HttpBindingDocumentation.fixedError(api.getComponents(), code, code);
            }
        });
        api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            var parameters = operation.getParameters();
            if (parameters != null && parameters.stream().anyMatch(parameter ->
                    "header".equals(parameter.getIn()) && AcquisitionCredentialResolver.HEADER_NAME.equalsIgnoreCase(parameter.getName()))
                    && parameters.stream().noneMatch(parameter -> name.equalsIgnoreCase(parameter.getName()))) {
                parameters.stream().filter(p -> AcquisitionCredentialResolver.HEADER_NAME.equalsIgnoreCase(p.getName()))
                        .map(Parameter::getSchema).filter(java.util.Objects::nonNull)
                        .forEach(schema -> schema.setMaxLength(AcquisitionCredentialResolver.MAX_LENGTH));
                operation.addParametersItem(selection);
                credentialErrors.forEach((status, code) -> HttpBindingDocumentation.addError(operation, status,
                        "#/components/schemas/" + code, text.get("filter.credentials." + status)));
            }
        }));
        // Publication and proof tokens apply only in the matching runtime context. A static route cannot grant them.
        for (String header : List.of(PagePublicationFilter.PAGE_HEADER, PagePublicationFilter.SLOT_HEADER, HookChallenges.TICKET_HEADER)) {
            api.getComponents().addParameters(header, new HeaderParameter().name(header)
                    .description(text.get("filter." + header)).schema(new StringSchema()));
        }
        for (String query : List.of(PagePublicationFilter.PAGE_QUERY, PagePublicationFilter.SLOT_QUERY)) {
            api.getComponents().addParameters(query, new QueryParameter().name(query)
                    .description(text.get("filter." + query)).schema(new StringSchema()));
        }
        api.addExtension("x-request-context-parameters", List.of(name, PagePublicationFilter.PAGE_HEADER, PagePublicationFilter.PAGE_QUERY,
                PagePublicationFilter.SLOT_HEADER, PagePublicationFilter.SLOT_QUERY, HookChallenges.TICKET_HEADER));
    }

    private static Schema<?> model(OpenAPI api, Class<?> type, String name, Map<String, String> text) {
        Schema<?> schema = ModelConverters.getInstance(true).readAllAsResolvedSchema(type).schema;
        schema.setTitle(name);
        schema.setDescription(text.get("filter." + name));
        schema.getProperties().forEach((field, property) -> property.setDescription(text.get("filter." + name + "." + field)));
        api.getComponents().addSchemas(name, schema);
        return schema;
    }

    private static Schema<?> ref(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }

    private static ApiResponse response(Map<String, String> text, String code, Schema<?> schema) {
        return new ApiResponse().description(text.get("filter.prepare." + code)).content(json(schema))
                .addHeaderObject(HttpOperationHooks.RESULT_HEADER, new Header().schema(new StringSchema())
                        .description(text.get("filter.X-Pixiv-Hook")));
    }
}
