package acceptance;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;

/**
 * Validate GET responses from a running instance. Pass its origin and optionally a JSON object
 * mapping OpenAPI path templates to arrays of concrete local URLs. An empty array skips a route.
 * Set PIXIVDL_KEY for a program key or PIXIVDL_SESSION for an administrator session.
 * Example samples: {"/api/gallery/artwork/{artworkId}":["/api/gallery/artwork/42"]}.
 */
public final class OpenApiGetProbe {
    private static final JsonMapper JSON = JsonMapper.shared();

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected origin [samples.json]");
        URI base = URI.create(args[0]);
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")) || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Expected an HTTP(S) origin");
        var samples = args.length == 2 ? JSON.readTree(Path.of(args[1])) : JSON.createObjectNode();
        if (!samples.isObject()) throw new IllegalArgumentException("Expected an object mapping GET paths to arrays of URLs");
        var request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(60));
        String key = System.getenv("PIXIVDL_KEY");
        String session = System.getenv("PIXIVDL_SESSION");
        String identity = "anonymous";
        if (key != null && !key.isBlank()) {
            if (session != null && !session.isBlank()) throw new IllegalArgumentException("Choose one probe identity");
            identity = "program key";
            request.header("Authorization", "Bearer " + key);
        } else if (session != null && !session.isBlank()) {
            identity = "administrator";
            request.header("X-Session-Token", session);
        }
        try (var client = HttpClient.newHttpClient()) {
            var spec = client.send(request.copy().uri(base.resolve("/api/openapi.json")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (spec.statusCode() != 200) throw new IllegalStateException("GET /api/openapi.json returned HTTP " + spec.statusCode());
            run(client, request, base, JSON.readTree(spec.body()), samples, identity);
        }
    }

    static void run(HttpClient client, HttpRequest.Builder request, URI base, JsonNode spec, JsonNode samples,
                    String identity) throws Exception {
        var paths = spec.required("paths");
        for (var sample : samples.properties()) {
            if (!paths.path(sample.getKey()).has("get") || !sample.getValue().isArray())
                throw new IllegalArgumentException("Expected a declared GET and an array of URLs for " + sample.getKey());
        }
        int valid = 0, invalid = 0, skipped = 0;
        for (var entry : paths.properties()) {
            String path = entry.getKey();
            var get = entry.getValue().path("get");
            if (get.isMissingNode()) continue;
            var urls = samples.get(path);
            var schema = get.at("/responses/200/content/application~1json/schema");
            String reason = null;
            if (schema.isMissingNode()) reason = "no JSON 200 schema";
            else if (path.endsWith("/token")) reason = "issues a token";
            else if (identity.equals("program key") && !get.has("x-program-permission")) reason = "outside program access";
            else if (identity.equals("administrator") && get.path("security").valueStream()
                    .noneMatch(s -> s.has("adminSessionHeader") || s.isEmpty())) reason = "outside administrator session access";
            else if (urls == null && (path.contains("{") || get.path("parameters").valueStream().anyMatch(p -> p.path("required").asBoolean(false))))
                reason = "needs path or query parameters";
            else if (urls == null && (path.startsWith("/api/pixiv/") || path.startsWith("/api/tts/")
                    || path.contains("/connectivity") || path.contains("/plugin-market/") || path.equals("/api/admin/update/check")))
                reason = "external source, supply a sample to include";
            else if (urls != null && urls.isEmpty()) reason = "skipped by samples";
            if (reason != null) {
                System.out.println("SKIP " + path + " " + reason);
                skipped++;
                continue;
            }
            var pattern = PathPatternParser.defaultInstance.parse(path);
            for (var sample : urls == null ? JSON.createArrayNode().add(path) : urls) {
                if (!sample.isString()) throw new IllegalArgumentException("Expected URL string for GET " + path);
                URI url = URI.create(sample.asString());
                if (url.isAbsolute() || url.getRawAuthority() != null || url.getFragment() != null
                        || !url.getRawPath().startsWith("/") || !url.normalize().equals(url)
                        || !pattern.matches(PathContainer.parsePath(url.getRawPath())))
                    throw new IllegalArgumentException("Sample must match local GET " + path);
                HttpResponse<InputStream> result;
                try {
                    result = client.send(request.copy().uri(base.resolve(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                } catch (IOException failure) {
                    System.err.println("FAIL " + path + " " + failure.getClass().getSimpleName());
                    invalid++;
                    continue;
                }
                try (var stream = result.body()) {
                    if (result.statusCode() >= 500) {
                        System.err.println("FAIL " + path + " HTTP " + result.statusCode());
                        invalid++;
                    } else if (result.statusCode() != 200) {
                        System.out.println("SKIP " + path + " HTTP " + result.statusCode());
                        skipped++;
                    } else if (!result.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                        System.err.println("FAIL " + path + " unexpected Content-Type");
                        invalid++;
                    } else {
                        try {
                            validate(spec, path, identity, JSON.readTree(stream));
                            valid++;
                        } catch (AssertionError | tools.jackson.core.JacksonException failure) {
                            System.err.println("FAIL " + path + " " + (failure instanceof AssertionError ? failure.getMessage() : "invalid JSON"));
                            invalid++;
                        }
                    }
                }
            }
        }
        System.out.printf("GET responses (%s): %d passed, %d failed, %d skipped%n", identity, valid, invalid, skipped);
        if (invalid != 0 || valid == 0) throw new AssertionError("No passing sample or invalid GET responses");
    }

    static void validate(JsonNode spec, String path, String identity, JsonNode response) {
        var schema = spec.required("paths").required(path).required("get").requiredAt("/responses/200/content/application~1json/schema");
        var errors = validator(spec, schema).validate(response);
        if (!errors.isEmpty()) {
            var locations = new LinkedHashSet<String>();
            for (var error : errors) locations.add(error.getInstanceLocation() + " -> " + error.getSchemaLocation() + " (" + error.getKeyword() + ")");
            throw new AssertionError(identity + " GET " + path + " " + String.join(", ", locations));
        }
    }

    static com.networknt.schema.Schema validator(JsonNode spec, JsonNode schema) {
        var document = JSON.createObjectNode();
        document.set("components", spec.required("components"));
        document.set("allOf", JSON.createArrayNode().add(schema));
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document);
    }
}
