package top.sywyar.pixivdownload.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import top.sywyar.pixivdownload.setup.ProgramKeyService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Projects request and execution grants after route and header permissions have been resolved. */
final class ProgramPermissionDocumentation {
    private static final String SOURCES = "#/x-schedule-sources";
    private static final String BATCH = "/api/download/batches";
    private static final String SCHEDULE = "/api/schedule/tasks";
    private static final Map<String, Object> DENIAL = Map.of("status", 403, "pointer", "/programPermission");

    private ProgramPermissionDocumentation() {}

    static void describeGrammar(OpenAPI document, Map<String, String> labels) {
        document.addExtension("x-program-permission-conditions", Map.of(
                "description", labels.get("permissionGrammar.description"),
                "phases", terms(labels, "phase", "route", "discovery", "submit", "execution"),
                "expression", Map.of("syntax", "<context>#<json-pointer>",
                        "description", labels.get("permissionGrammar.expression"),
                        "contexts", terms(labels, "context", "request", "query", "task", "batch", "item")),
                "fields", terms(labels, "field", "permission", "ownerFrom", "permissionId", "header", "effect",
                        "catalog", "key", "keyFrom", "value", "when", "field", "keyedBy", "recheckField",
                        "reportedIn", "operation", "status", "pointer", "codePointer")));
    }

    private static Map<String, String> terms(Map<String, String> labels, String group, String... names) {
        var terms = new LinkedHashMap<String, String>();
        for (String name : names) terms.put(name, labels.get("permissionGrammar." + group + "." + name));
        return terms;
    }

    static void describe(Paths paths) {
        paths.forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            var extensions = operation.getExtensions();
            if (method == PathItem.HttpMethod.HEAD || extensions == null
                    || !(extensions.get("x-program-permission") instanceof String route)) return;
            var stages = new LinkedHashMap<String, Object>();
            var submit = new ArrayList<Map<String, Object>>();
            stages.put("route", route);
            describeToolOwner(path, route, submit);
            boolean post = method == PathItem.HttpMethod.POST;
            boolean scheduleDefinition = post && path.equals(SCHEDULE)
                    || method == PathItem.HttpMethod.PUT && path.equals(SCHEDULE + "/{id}");
            boolean createBatch = post && path.equals(BATCH);
            Object fixedSource = extensions.get("x-manual-batch-source");
            boolean retryBatch = post && (path.equals(BATCH + "/{id}/resume") || path.equals(BATCH + "/{id}/retry"));

            boolean scheduleMetadata = post && path.equals(ProgramKeyService.SCHEDULE_AUTHOR_METADATA);
            if (scheduleDefinition || createBatch || scheduleMetadata) {
                endpoint(paths, "/api/schedule/sources", PathItem.HttpMethod.GET).ifPresent(link -> stages.put("discovery", link));
            }
            if (path.equals(SCHEDULE) && method == PathItem.HttpMethod.GET) {
                submit.add(Map.of("ownerFrom", "item#/sourceOwnerPluginId", "permissionId", "schedule.manage", "effect", "filter"));
            } else if (path.startsWith(SCHEDULE + "/{id}")) {
                submit.add(Map.of("ownerFrom", "task#/sourceOwnerPluginId", "permissionId", "schedule.manage", "reportedIn", DENIAL));
            }
            if (scheduleMetadata) {
                submit.add(Map.of("when", Map.of("keyFrom", ProgramKeyService.TASK_ID_POINTER), "ownerFrom", "task#/sourceOwnerPluginId",
                        "permissionId", "schedule.manage", "reportedIn", DENIAL));
            }
            if (scheduleDefinition) submit.add(sourceGrant("schedulePermission", "key", "sourceType"));
            if (post && path.equals("/api/schedule/credential-policies/actions")) {
                submit.add(Map.of("ownerFrom", "request#/ownerPluginId", "permissionId", "schedule.manage", "reportedIn", DENIAL));
            }
            if (post && path.equals("/api/download/tasks")) {
                submit.add(Map.of("catalog", "#/x-download-submissions", "key", "workType",
                        "field", "x-program-permission", "reportedIn", DENIAL));
            }
            if (createBatch || retryBatch || fixedSource instanceof String) {
                String selector = "key";
                String value = "sourceType";
                if (retryBatch) {
                    selector = "keyFrom";
                    value = "batch#/sourceType";
                } else if (fixedSource instanceof String type) {
                    selector = "value";
                    value = type;
                    endpoint(paths, BATCH, PathItem.HttpMethod.POST).ifPresent(link ->
                            submit.add(Map.of("permission", link.get("permission"), "reportedIn", DENIAL)));
                }
                submit.add(sourceGrant("batchSubmitPermission", selector, value));
                var execution = new LinkedHashMap<String, Object>(Map.of("catalog", SOURCES, selector, value,
                        "field", "executePermissionsByWorkType", "keyedBy", "workType", "recheckField", "batchSubmitPermission"));
                endpoint(paths, BATCH + "/{id}", PathItem.HttpMethod.GET).ifPresent(link -> {
                    var result = new LinkedHashMap<>(link);
                    result.put("status", 200);
                    result.put("pointer", "/programPermission");
                    result.put("codePointer", "/code");
                    execution.put("reportedIn", result);
                });
                stages.put("execution", execution);
            }
            if (operation.getParameters() != null) operation.getParameters().forEach(parameter -> {
                var metadata = parameter.getExtensions();
                if ("header".equals(parameter.getIn()) && metadata != null
                        && metadata.get("x-program-permission") instanceof String permission) {
                    submit.add(Map.of("header", parameter.getName(), "permission", permission, "reportedIn", DENIAL));
                }
            });
            if (!submit.isEmpty()) {
                stages.put("submit", submit);
                operation.addExtension("x-program-permissions", stages);
            }
        }));
    }

    private static void describeToolOwner(String path, String route, List<Map<String, Object>> submit) {
        String owner = switch (path) {
            case "/api/tools/media", "/api/tools/file-import" -> "item#/identity/pluginId";
            case "/api/tools/media/tasks/current", "/api/tools/file-import/tasks/current" -> "query#/pluginId";
            case "/api/tools/media/capabilities" -> "request#/pluginId";
            case "/api/tools/media/previews", "/api/tools/media/tasks", "/api/tools/media/tasks/cancel",
                 "/api/tools/file-import/previews", "/api/tools/file-import/tasks", "/api/tools/file-import/tasks/cancel",
                 "/api/tools/file-import/completion/acknowledge" -> "request#/identity/pluginId";
            default -> null;
        };
        if (owner == null) return;
        String permission = route.substring(route.indexOf(':') + 1);
        submit.add(owner.startsWith("item#")
                ? Map.of("ownerFrom", owner, "permissionId", permission, "effect", "filter")
                : Map.of("ownerFrom", owner, "permissionId", permission, "reportedIn", DENIAL));
    }

    private static Map<String, Object> sourceGrant(String field, String selector, String value) {
        return Map.of("catalog", SOURCES, selector, value, "field", field, "reportedIn", DENIAL);
    }

    private static Optional<Map<String, Object>> endpoint(Paths paths, String path, PathItem.HttpMethod method) {
        var item = paths.get(path);
        var target = item == null ? null : item.readOperationsMap().get(method);
        if (target == null || target.getExtensions() == null
                || !(target.getExtensions().get("x-program-permission") instanceof String permission)) return Optional.empty();
        return Optional.of(Map.of("operation", method + " " + path, "permission", permission));
    }
}
