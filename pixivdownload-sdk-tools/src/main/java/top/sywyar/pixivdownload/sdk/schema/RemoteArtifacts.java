package top.sywyar.pixivdownload.sdk.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 一份操作描述同时生成浏览器客户端、声明文件和 Spring 薄适配器。 */
final class RemoteArtifacts {
    static Map<String, byte[]> generate(Map<String, ObjectNode> contracts) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String runtime;
        String generator;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Class<?> tool : List.of(RemoteArtifacts.class, RemoteSource.class, ValueSchemaProcessor.class, RemoteAdapterGenerator.class)) {
                try (var input = tool.getResourceAsStream(tool.getSimpleName() + ".class")) {
                    if (input == null) throw new IOException("SDK_REMOTE_RUNTIME_MISSING");
                    digest.update(input.readAllBytes());
                }
            }
            try (var input = RemoteArtifacts.class.getResourceAsStream("/remote/client-runtime.mjs")) {
                if (input == null) throw new IOException("SDK_REMOTE_RUNTIME_MISSING");
                byte[] bytes = input.readAllBytes(); digest.update(bytes);
                runtime = new String(bytes, StandardCharsets.UTF_8);
            }
            generator = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        for (var entry : contracts.entrySet()) {
            String name = entry.getKey();
            ObjectNode contract = entry.getValue();
            contract.put("generator", generator);
            byte[] description = SchemaFiles.JSON.writeValueAsBytes(contract);
            String identity;
            try { identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(description)); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            contract.put("identity", identity);
            add(files, name + ".remote.json", SchemaFiles.JSON.writeValueAsString(contract));
            add(files, name + ".mjs", "const contract = JSON.parse("
                    + SchemaFiles.JSON.writeValueAsString(SchemaFiles.JSON.writeValueAsString(contract)) + ");\n" + runtime);
            add(files, name + ".d.mts", types(contract));
            add(files, name + ".java.src", adapter(contract));
        }
        return files;
    }

    private static void add(Map<String, byte[]> files, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SchemaFiles.MAX_BYTES) throw new IOException("SDK_REMOTE_OUTPUT_LIMIT");
        files.put(name, bytes);
    }

    private static String typeName(String name) { return "T" + HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8)); }

    private static String type(JsonNode schema) throws IOException {
        if (schema.has("anyOf")) return "(" + type(schema.path("anyOf").get(0)) + " | null)";
        if (schema.has("$ref")) return typeName(schema.path("$ref").asText().substring("#/$defs/".length()));
        return switch (schema.path("type").asText()) {
            case "boolean" -> "boolean";
            case "integer" -> "number";
            case "string" -> {
                if (!schema.has("enum")) yield "string";
                List<String> values = new ArrayList<>();
                for (var item : schema.path("enum")) values.add(SchemaFiles.JSON.writeValueAsString(item.asText()));
                yield String.join(" | ", values);
            }
            case "array" -> "ReadonlyArray<" + type(schema.path("items")) + ">";
            case "object" -> {
                if (!schema.has("properties")) yield "Readonly<Record<string, " + type(schema.path("additionalProperties")) + ">>";
                Set<String> required = new HashSet<>();
                schema.path("required").forEach(n -> required.add(n.asText()));
                StringBuilder result = new StringBuilder("{ ");
                var fields = schema.path("properties").fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    result.append("readonly ").append(SchemaFiles.JSON.writeValueAsString(field.getKey()))
                            .append(required.contains(field.getKey()) ? ": " : "?: ").append(type(field.getValue())).append("; ");
                }
                yield result.append("}").toString();
            }
            default -> throw new IOException("SDK_REMOTE_TYPE");
        };
    }

    private static String types(ObjectNode contract) throws IOException {
        Map<String, JsonNode> definitions = new TreeMap<>();
        for (JsonNode operation : contract.path("operations")) for (String direction : List.of("request", "response")) {
            operation.path(direction).path("$defs").fields().forEachRemaining(e -> definitions.put(e.getKey(), e.getValue()));
        }
        StringBuilder result = new StringBuilder("// 契约身份: ").append(contract.path("identity").asText()).append("\n");
        for (var definition : definitions.entrySet())
            result.append("type ").append(typeName(definition.getKey())).append(" = ").append(type(definition.getValue())).append(";\n");
        var operations = contract.path("operations").fields();
        while (operations.hasNext()) {
            var operation = operations.next();
            result.append("export type ").append(operation.getKey()).append("_Request = ").append(type(operation.getValue().path("request"))).append(";\n")
                    .append("export type ").append(operation.getKey()).append("_Response = ").append(type(operation.getValue().path("response"))).append(";\n");
        }
        result.append("export declare class RemoteError extends Error { readonly code: string; }\nexport declare const contractIdentity: string;\nexport declare function createClient(fetcher?: typeof fetch): {\n");
        contract.path("operations").fieldNames().forEachRemaining(name -> result.append("  ").append(name).append("(request: ").append(name)
                .append("_Request, options?: { signal?: AbortSignal }): Promise<").append(name).append("_Response>;\n"));
        return result.append("};\n").toString();
    }

    private static String adapter(ObjectNode contract) {
        String service = contract.path("service").asText();
        String pack = "pixivdownload.generated.remote";
        String simple = service.substring(service.lastIndexOf('.') + 1) + "RemoteAdapter";
        StringBuilder result = new StringBuilder("package ").append(pack).append(";\n")
                .append("import top.sywyar.pixivdownload.plugin.api.web.*;\n")
                .append("import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;\n")
                .append("import org.springframework.web.bind.annotation.*;\n")
                .append("import org.springframework.http.ResponseEntity;\n")
                .append("@RestController public final class ").append(simple).append(" {\n")
                .append("  private final RemoteOperations operations;\n")
                .append("  public ").append(simple).append("(RemoteOperations operations) { this.operations = operations; }\n")
                .append("  public static java.util.List<WebRouteContribution> routes() { return java.util.List.of(");
        List<String> routes = new ArrayList<>();
        var operations = contract.path("operations").fields();
        while (operations.hasNext()) {
            var operation = operations.next();
            routes.add("new WebRouteContribution(\"" + operation.getValue().path("path").asText()
                    + "\", AccessPolicy.ADMIN, java.util.Set.of(HttpMethod.POST), false)");
        }
        result.append(String.join(",", routes)).append("); }\n");
        operations = contract.path("operations").fields();
        int handler = 0;
        while (operations.hasNext()) {
            var operation = operations.next();
            result.append("  @PostMapping(value=\"").append(operation.getValue().path("path").asText())
                    .append("\", produces=\"application/json\") public ResponseEntity<String> remoteOperation").append(handler++)
                    .append("() {\n")
                    .append("    var response = operations.invoke(new ServiceKey<>(\"").append(contract.path("owner").asText())
                    .append("\", ").append(service).append(".class), \"").append(operation.getKey()).append("\", \"")
                    .append(contract.path("identity").asText()).append("\");\n")
                    .append("    return ResponseEntity.status(response.status()).header(\"X-Pixiv-Contract\", response.identity())")
                    .append(".contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(response.json());\n  }\n");
        }
        return result.append("}\n").toString();
    }
}
