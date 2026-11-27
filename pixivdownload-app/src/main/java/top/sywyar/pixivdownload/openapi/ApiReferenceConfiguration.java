package top.sywyar.pixivdownload.openapi;

import top.sywyar.pixivdownload.common.PixivConnectivityProbe;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleCredentialPolicyView;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleTaskView;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.setup.ProgramKeysController;
import top.sywyar.pixivdownload.transfer.EnvironmentTransferController;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryKeyPreview;
import top.sywyar.pixivdownload.plugin.api.gui.TrustedKeyConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixToolsController;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import io.swagger.v3.oas.models.media.ArraySchema;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.sdk.community.content.MarketContent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springdoc.webmvc.core.providers.SpringWebMvcProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.setup.ProgramAccess;

import top.sywyar.pixivdownload.i18n.WebI18nService;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.*;
import java.lang.annotation.Annotation;

@Configuration(proxyBeanMethods = false)
class ApiReferenceConfiguration {
    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(WorkVisibilityScope.class);
        SpringDocUtils.getConfig().replaceWithClass(top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse.class,
                top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse.Basic.class);
        // Jackson 3 tree nodes are JSON values on the wire. Their Java predicate getters are not fields.
        SpringDocUtils.getConfig().replaceWithSchema(tools.jackson.databind.JsonNode.class,
                new io.swagger.v3.oas.models.media.JsonSchema());
        SpringDocUtils.getConfig().replaceWithSchema(java.nio.file.Path.class, new StringSchema().format("uri"));
    }
    @Bean ModelConverter nullableHttpProperties() {
        return (type, context, chain) -> {
            if (!chain.hasNext()) return null;
            var next = chain.next();
            var annotations = type.getCtxAnnotations();
            if (!type.isSchemaProperty() || annotations == null || Arrays.stream(annotations).noneMatch(HttpApiNullable.class::isInstance))
                return next.resolve(type, context, chain);

            // Swagger forwards property annotations to collection items and map values.
            var property = new AnnotatedType();
            BeanUtils.copyProperties(type, property);
            property.setCtxAnnotations(Arrays.stream(annotations).filter(a -> !(a instanceof HttpApiNullable)).toArray(Annotation[]::new));
            var schema = next.resolve(property, context, chain);
            return schema == null ? null : nullable(schema);
        };
    }

    private static Schema<?> nullable(Schema<?> schema) {
        // Keep the original type, enum or reference intact for non-null values.
        return new ComposedSchema().addAnyOfItem(schema).addAnyOfItem(new Schema<>().types(Set.of("null")));
    }

    private static void nullableProperties(Components components, Class<?> model, String... fields) {
        Schema<?> schema = components.getSchemas().get(model.getCanonicalName());
        if (schema != null) {
            for (String field : fields) schema.getProperties().computeIfPresent(field, (key, property) -> nullable(property));
        }
    }

    private static void describeClientModels(Components components) {
        nullableProperties(components, PixivConnectivityProbe.Result.class, "errorType");
        nullableProperties(components, ScheduleCredentialPolicyView.class, "ownerPluginId", "policyId",
                "accountKey", "publicationId", "statusCode", "acknowledgedEventTime", "accountActionsSchemaJson");
        nullableProperties(components, ScheduleTaskView.class, "sourceActivationToken", "intervalMinutes",
                "cronExpr", "proxy", "nextRunTime", "lastRunTime", "outcomeCode", "outcomeMessage",
                "suspendReason", "suspendCode", "suspendDetailJson", "runState");
        nullableProperties(components, ScheduledTaskPresentation.class, "title", "summary");
        nullableProperties(components, ScheduledWorkPresentation.class, "title", "author", "thumbnailReference");
        var schemas = components.getSchemas();
        var states = List.of(ScheduleRunQueue.STATUS_PENDING, ScheduleRunQueue.STATUS_PAUSED, ScheduleRunQueue.STATUS_DOWNLOADED,
                ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, ScheduleRunQueue.STATUS_SKIPPED_FILTER, ScheduleRunQueue.STATUS_FAILED);
        for (var type : List.of(ScheduleRunQueue.Item.class, ScheduleQueueView.Item.class)) {
            Schema<?> item = schemas.get(type.getCanonicalName());
            if (item != null) item.getProperties().get("status").setEnum(states);
        }
        Schema<?> action = schemas.get(WorkActionResult.class.getCanonicalName());
        if (action != null) action.getProperties().get("status").setEnum(List.of(
                WorkActionResult.SUCCESS, WorkActionResult.FAILED, WorkActionResult.SKIPPED, WorkActionResult.EXISTS));
        Schema<?> result = schemas.get(ScheduledWorkResult.class.getCanonicalName());
        if (result != null) {
            var code = result.getProperties().get("resultCode");
            code.setPattern(ScheduledWorkResult.RESULT_CODE_PATTERN);
            code.setExamples(List.of("work.completed", "work.already-completed"));
        }
        describeToolStates(schemas);
        describeNonemptySelections(schemas);
        ProgramKeysController.describePolicySchema(components);
    }

    private static void describeToolStates(Map<String, Schema> schemas) {
        Schema<?> preview = schemas.get(RepositoryKeyPreview.class.getCanonicalName());
        if (preview != null) preview.getProperties().get("state").setEnum(List.of("ACTIVE", "RETIRED"));
        Schema<?> keys = schemas.get(TrustedKeyConfigEntry.class.getCanonicalName());
        if (keys != null) keys.getProperties().get("state").setExamples(List.of("ACTIVE", "RETIRED"));
        Schema<?> migration = schemas.get(DesktopDatabaseSettings.MigrationState.class.getCanonicalName());
        if (migration != null) migration.getProperties().get("status").setExamples(List.of("", "PENDING", "RUNNING", "SWITCHING", "SUCCEEDED", "FAILED"));
        Schema<?> store = schemas.get(DesktopDatabaseSettings.MigrationStore.class.getCanonicalName());
        if (store != null) store.getProperties().get("phase").setExamples(List.of("READY", "SAME_TARGET", "REPLACE_TARGET", "BLOCKED", "COMMITTED"));
        Schema<?> media = schemas.get(DesktopMediaTool.Status.class.getCanonicalName());
        if (media != null) media.getProperties().get("state").setExamples(List.of("idle", "scanning", "running", "completed", "cancelled", "failed", "unavailable"));
    }

    private static void describeNonemptySelections(Map<String, Schema> schemas) {
        Schema<?> restore = schemas.get(EnvironmentTransferController.Restore.class.getCanonicalName());
        if (restore != null) {
            var included = new io.swagger.v3.oas.models.media.BooleanSchema();
            included.setConst(true);
            restore.addAnyOfItem(new ObjectSchema().additionalProperties(true).addProperty("includeDatabases", included).addRequiredItem("includeDatabases"));
            for (String field : List.of("selected", "fileGroups"))
                restore.addAnyOfItem(new ObjectSchema().additionalProperties(true).addProperty(field, new ArraySchema().minItems(1)).addRequiredItem(field));
        }
        Schema<?> edits = schemas.get(PathPrefixToolsController.Changes.class.getCanonicalName());
        if (edits != null) {
            for (String field : List.of("updates", "registerPaths"))
                edits.addAnyOfItem(new ObjectSchema().additionalProperties(true).addProperty(field, new ArraySchema().minItems(1)).addRequiredItem(field));
        }
    }

    @Bean SpringWebMvcProvider liveApiMappings(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return new SpringWebMvcProvider(Optional.empty()) {
            @Override public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
                return new LinkedHashMap<>(mappings.getObject().getHandlerMethods());
            }
        };
    }

    @Bean OpenApiCustomizer apiReferencePermissions(RouteAccessRegistry routes, WebI18nService i18n,
            ApiOperationDocumentation documentation, SpringWebMvcProvider mappings, DownloadLifecycleRegistry downloads,
            FilterOperationDocumentation filters, ScheduleCapabilityRegistry schedules) {
        return api -> {
            var text = i18n.loadBundle("openapi", LocaleContextHolder.getLocale()).getMessages();
            // Sections follow a reader's order: get access, check grants, poll and write, handle errors, then compare fields.
            api.setInfo(new Info().title(text.get("title")).version("1")
                    .description("## " + text.get("heading.access") + "\n\n" + text.get("intro")
                            + "\n\n" + text.get("example") + "\n\n```sh\ncurl -H \"Authorization: Bearer ${PROGRAM_KEY}\" "
                            + "https://your-server/api/settings\n```\n" + text.get("example.scope")
                            + "\n\n## " + text.get("heading.permissions") + "\n\n" + text.get("permissions")
                            + "\n\n## " + text.get("heading.integration") + "\n\n" + text.get("integration")
                            + "\n\n## " + text.get("heading.errors") + "\n\n" + text.get("response.serverFailure")
                            + "\n\n" + text.get("missing") + "\n\n" + text.get("comparison") + "\n\n"
                            + text.get("pagination") + "\n\n" + text.get("comparison.task") + """
                                \n\n```json
                                {
                                  "requestId": "3aa71664-8156-401a-a937-e196989a9c62",
                                  "workType": "artwork",
                                  "workId": "42",
                                  "options": {"bookmark": "false", "mediaQuality": "80", "mediaWebpLossless": "false"}
                                }
                                ```"""
                            + "\n\n" + text.get("comparison.batch") + """
                                \n\n```json
                                {
                                  "name": "42",
                                  "sourceType": "explicit-list",
                                  "activationToken": "<activationToken>",
                                  "definition": {
                                    "kind": "mixed",
                                    "source": {"acquisitionMode": "single-import", "works": [{"kind": "illust", "id": "42"}]},
                                    "download": {"bookmark": false, "mediaQuality": 80, "mediaWebpLossless": false}
                                  }
                                }
                                ```"""));
            api.setExternalDocs(new io.swagger.v3.oas.models.ExternalDocumentation().url(text.get("guide.url")));
            api.setServers(List.of(new Server().url("/")));
            if (api.getComponents() == null) api.setComponents(new Components());
            ApiReferenceSecurity.schemes(api.getComponents(), text);
            HttpBindingDocumentation.transportSchemas(api.getComponents(), text);
            HttpBindingDocumentation.fixedError(api.getComponents(), "HttpMaintenanceError", "auth.maintenance");
            HttpBindingDocumentation.fixedError(api.getComponents(), "HttpPluginQuiescedError", "plugin.unavailable.quiesced");
            var instanceAccess = ApiReferenceSecurity.InstanceAccess.RENDERING.get();
            var taskRoute = api.getPaths() == null ? null : api.getPaths().get("/api/download/tasks");
            Operation submit = taskRoute == null ? null : taskRoute.getPost();
            var submissions = describeSubmissions(api, submit, downloads.submissionTypes(), text.get("submission.workType"));
            // These shared models are also consumed independently of the HTTP API.
            nullableProperties(api.getComponents(), WorkTag.class, "tagId", "translatedName");
            nullableProperties(api.getComponents(), VersionRequirement.class, "raw");
            nullableProperties(api.getComponents(), MarketContent.Document.class, "sourceUrl");
            describeClientModels(api.getComponents());
            documentation.describeModels(api, mappings.getHandlerMethods().values(), LocaleContextHolder.getLocale());
            submissions.forEach((type, schema) -> copySubmissionLabels(schema,
                    api.getComponents().getSchemas().get("DownloadSubmission." + type)));
            ScheduleSourceDocumentation.describe(api, schedules.snapshotView());
            if (api.getPaths() == null) return;
            assignOperationIds(api.getPaths());
            var sections = new TreeMap<String, io.swagger.v3.oas.models.tags.Tag>();
            var usedSections = new TreeSet<String>();
            if (api.getTags() != null) api.getTags().forEach(tag -> sections.put(tag.getName(), tag));
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                var extensions = operation.getExtensions();
                Object sectionDescription = extensions == null ? null : extensions.remove(ApiOperationDocumentation.SECTION_DESCRIPTION);
                Object sectionTitle = extensions == null ? null : extensions.remove(ApiOperationDocumentation.SECTION_TITLE);
                if (sectionTitle != null) {
                    var tag = sections.computeIfAbsent(operation.getTags().getFirst(),
                            name -> new io.swagger.v3.oas.models.tags.Tag().name(name));
                    tag.addExtension("x-displayName", sectionTitle);
                    if (sectionDescription instanceof String description) tag.setDescription(description);
                }
                if (path.startsWith("/api/gui/") && operation.getSummary() != null) {
                    operation.setSummary(text.get("summary.gui").replace("{summary}", operation.getSummary()));
                }
                var verb = HttpMethod.valueOf(method.name());
                routes.resolve(path, verb).ifPresent(route -> {
                    var policy = route.route().accessPolicy().name();
                    operation.addExtension("x-access-policy", policy);
                    operation.addExtension("x-plugin-id", route.pluginId());
                    if (sectionTitle == null && (operation.getTags() == null
                            || operation.getTags().stream().noneMatch(sections::containsKey))) {
                        operation.setTags(List.of(route.pluginId()));
                        sections.computeIfAbsent(route.pluginId(), name -> new io.swagger.v3.oas.models.tags.Tag().name(name));
                    }
                    var permission = route.route().programPermissions().get(verb);
                    String detail = ApiReferenceSecurity.describe(operation, route.route(), path, verb, instanceAccess, text);
                    boolean self = ProgramAccess.isSelf(route, verb);
                    if (self) {
                        operation.setSecurity(List.of(new io.swagger.v3.oas.models.security.SecurityRequirement()
                                .addList("programKey")));
                        operation.addExtension("x-program-key-self", true);
                        detail = text.get("key.self");
                    } else if (permission == null) {
                        detail += " " + text.get("key.unsupported");
                    } else {
                        String id = route.pluginId() + ":" + permission.id();
                        detail += " " + text.get("key.required").replace("{permission}", id);
                        operation.addExtension("x-program-permission", id);
                    }
                    ApiReferenceSecurity.errors(operation, permission != null, self, text);
                    // AuthFilter pauses MVC API calls before authentication; filter-owned operations are added separately.
                    if (verb != HttpMethod.OPTIONS && path.startsWith("/api/")) {
                        HttpBindingDocumentation.addError(operation, "503", "#/components/schemas/HttpMaintenanceError", text.get("response.maintenance"));
                        if (route.requestOwner() != null)
                            HttpBindingDocumentation.addError(operation, "503", "#/components/schemas/HttpPluginQuiescedError", text.get("response.quiesced"));
                        operation.getResponses().get("503").addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                                .schema(new io.swagger.v3.oas.models.media.IntegerSchema().minimum(java.math.BigDecimal.ZERO)));
                    }
                    String description = operation.getDescription();
                    operation.setDescription((description == null ? "" : description + "\n\n") + detail);
                });
                if (operation.getTags() != null) usedSections.addAll(operation.getTags());
            }));
            // The variable catalog is a reference section with no HTTP operation of its own.
            usedSections.add(WorkTemplateDocumentation.CATALOG_TAG);
            sections.keySet().retainAll(usedSections);
            api.setTags(List.copyOf(sections.values()));
            filters.describe(api, routes, LocaleContextHolder.getLocale());
            ProgramPermissionDocumentation.describe(api.getPaths());
            api.getPaths().values().stream().map(io.swagger.v3.oas.models.PathItem::getHead)
                    .filter(java.util.Objects::nonNull).forEach(head ->
                            head.getResponses().values().forEach(response -> response.setContent(null)));
            api.getPaths().values().forEach(item -> item.readOperations().forEach(operation -> {
                ApiOperationDocumentation.describeEventStreams(operation);
                documentation.finishDetails(operation, operation == submit ? submissions : Map.of(), LocaleContextHolder.getLocale());
                var limited = operation.getResponses().get("429");
                if (limited != null) limited.addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                        .schema(new io.swagger.v3.oas.models.media.IntegerSchema().minimum(java.math.BigDecimal.ZERO)));
            }));
            RelatedOperations.describe(api.getPaths());
        };
    }

    static void assignOperationIds(Paths paths) {
        record Route(String key, Operation operation, boolean explicit) {}
        var routesById = new TreeMap<String, List<Route>>();
        paths.forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            var extensions = operation.getExtensions();
            var explicit = extensions == null ? null : extensions.remove(ApiOperationDocumentation.EXPLICIT_ID);
            String name = explicit instanceof String id ? id : operationId(method, path);
            operation.setOperationId(name);
            routesById.computeIfAbsent(name, unused -> new ArrayList<>())
                    .add(new Route(method.name() + " " + path, operation, explicit != null));
        }));
        var assigned = new HashSet<>(routesById.keySet());
        routesById.forEach((name, routes) -> {
            if (routes.size() < 2) return;
            // Distinct paths such as /foo-bar and /foo/bar can produce the same SDK name.
            routes.sort(Comparator.comparing(Route::explicit).reversed().thenComparing(Route::key));
            for (var route : routes.subList(1, routes.size())) {
                String suffix = "Route" + Integer.toUnsignedString(route.key().hashCode(), 36);
                String candidate = name + suffix;
                while (!assigned.add(candidate)) candidate += suffix;
                route.operation().setOperationId(candidate);
            }
        });
    }

    private static String operationId(PathItem.HttpMethod method, String path) {
        var id = new StringBuilder(method.name().toLowerCase(Locale.ROOT));
        // Include the method and route so aliases and optional plugins have independent, stable names.
        for (String word : path.replaceFirst("^/api/", "").split("[^A-Za-z0-9{]+")) {
            if (word.isEmpty()) continue;
            if (word.startsWith("{")) {
                id.append("By");
                word = word.substring(1);
            }
            if (!word.isEmpty()) id.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return id.toString();
    }

    private static Map<String, ObjectNode> describeSubmissions(
            OpenAPI api, Operation submit, Map<String, DownloadLifecycleRegistry.SubmissionType> types, String workTypeDescription) {
        if (submit == null || submit.getRequestBody() == null) return Map.of();
        var media = submit.getRequestBody().getContent().get("application/json");
        if (media == null) return Map.of();
        var catalog = new TreeMap<String, ObjectNode>();
        var variants = new ComposedSchema();
        types.forEach((type, provider) -> {
            try {
                var mapper = io.swagger.v3.core.util.Json31.mapper();
                var schema = provider.schemaJson() == null ? mapper.createObjectNode()
                        : (ObjectNode) mapper.readTree(provider.schemaJson());
                if (provider.schemaJson() == null) {
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("workType").put("const", type)
                            .put("description", workTypeDescription);
                }
                schema.put("x-input-schema-available", provider.schemaJson() != null);
                schema.put("x-program-permission", provider.pluginId() + ":downloads.create");
                String component = "DownloadSubmission." + type;
                schema.put("x-openapi-schema", "#/components/schemas/" + component);
                catalog.put(type, schema);
                var model = mapper.treeToValue(schema, Schema.class);
                if (model.get$id() == null) model.set$id("urn:pixivdownload:submission:" + type);
                api.getComponents().addSchemas(component, model.title(type));
                var reference = new Schema<>().$ref("#/components/schemas/" + component);
                if (type.equals(schema.at("/properties/workType/const").asText())) {
                    variants.addOneOfItem(reference);
                } else {
                    var match = new StringSchema().description(workTypeDescription);
                    match.setConst(type);
                    variants.addOneOfItem(new ComposedSchema()
                            .addAllOfItem(new ObjectSchema().addProperty("workType", match)).addAllOfItem(reference));
                }
                var examples = schema.path("examples");
                if (examples.isArray() && !examples.isEmpty()) {
                    media.addExamples(type, new io.swagger.v3.oas.models.examples.Example().value(examples.get(0)));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalStateException("Invalid registered download schema: " + type, invalid);
            }
        });
        if (!types.isEmpty()) {
            media.setSchema(new ComposedSchema().addAllOfItem(media.getSchema()).addAllOfItem(variants));
        }
        api.addExtension("x-download-submissions", catalog);
        return catalog;
    }

    /** Keep the provider's JSON Schema keywords while sharing the descriptions shown on the operation. */
    private static void copySubmissionLabels(ObjectNode node, Schema<?> schema) {
        if (schema.getTitle() != null) node.put("title", schema.getTitle());
        if (schema.getDescription() != null) node.put("description", schema.getDescription());
        if (schema.getProperties() != null) schema.getProperties().forEach((name, property) -> {
            if (node.path("properties").get(name) instanceof ObjectNode child)
                copySubmissionLabels(child, property);
        });
    }
}
