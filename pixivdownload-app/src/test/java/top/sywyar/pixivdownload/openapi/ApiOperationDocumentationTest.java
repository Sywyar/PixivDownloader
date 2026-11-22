package top.sywyar.pixivdownload.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;
import top.sywyar.pixivdownload.i18n.CatalogLocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.LocaleCatalogLoader;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

import static org.assertj.core.api.Assertions.assertThat;

class ApiOperationDocumentationTest {
    @HttpApiGroup("inline-response-test")
    static class Reports {
        @GetMapping
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", useReturnTypeSchema = true,
                links = @io.swagger.v3.oas.annotations.links.Link(name = "details", operationId = "getDetails"))
        public void list() {}
        @org.springframework.web.bind.annotation.PostMapping public void first() {}
        @org.springframework.web.bind.annotation.PostMapping public void second() {}
    }

    @Test
    void localizesInlineResponseFieldsByStatusWithoutOverwritingReferencedSchemas() throws Exception {
        var code = new StringSchema();
        var reference = new Schema<>().$ref("#/components/schemas/Detail").description("Detail supplied by provider");
        var item = new ObjectSchema().addProperty("code", code).addProperty("detail", reference);
        var payload = new ObjectSchema().additionalProperties(new ArraySchema().items(item));
        var errorCode = new StringSchema().description("Failure supplied by controller");
        var responses = new ApiResponses()
                .addApiResponse("200", response(payload))
                .addApiResponse("400", response(new ObjectSchema().addProperty("code", errorCode)))
                .addApiResponse("204", new ApiResponse());

        var docs = new ApiOperationDocumentation(new CatalogLocaleBundlePolicy(new LocaleCatalogLoader(null).load()));
        docs.customize(new Operation().responses(responses), new HandlerMethod(new Reports(), "list"));

        assertThat(responses.get("200").getLinks().get("details").getOperationId()).isEqualTo("getDetails");
        var localized = responses.get("200").getContent().get("application/json").getSchema();
        var groups = (Schema<?>) localized.getAdditionalProperties();
        assertThat(groups.getItems().getProperties().get("code").getDescription()).isEqualTo("Status within this group");
        assertThat(code.getDescription()).isNull();
        assertThat(reference.get$ref()).isEqualTo("#/components/schemas/Detail");
        assertThat(reference.getDescription()).isEqualTo("Detail supplied by provider");
        assertThat(errorCode.getDescription()).isEqualTo("Failure supplied by controller");
        assertThat(responses.get("204").getContent()).isNull();
    }

    @Test
    void keepsMultipartDescriptionsLocalToEachOperation() throws Exception {
        Schema<?> shared = new ObjectSchema().addProperty("file", new StringSchema().format("binary")).addRequiredItem("file");
        var first = new MediaType().schema(shared);
        var second = new MediaType().schema(shared);
        var docs = new ApiOperationDocumentation(new CatalogLocaleBundlePolicy(new LocaleCatalogLoader(null).load()));
        docs.customize(upload(first), new HandlerMethod(new Reports(), "first"));
        docs.customize(upload(second), new HandlerMethod(new Reports(), "second"));

        Schema<?> audio = first.getSchema();
        Schema<?> icon = second.getSchema();
        assertThat(audio.getProperties().get("file").getDescription()).isEqualTo("Reference recording");
        assertThat(icon.getProperties().get("file").getDescription()).isEqualTo("Collection image");
        assertThat(shared.getProperties().get("file").getDescription()).isNull();
        assertThat(audio.getProperties().get("file").getFormat()).isEqualTo("binary");
        assertThat(audio.getRequired()).containsExactly("file");
    }

    private static Operation upload(MediaType media) {
        return new Operation().requestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                .content(new Content().addMediaType("multipart/form-data", media)));
    }

    @Test
    void detailsFollowDocumentedProvidersAndDisappearWhenNoneContribute() {
        var docs = new ApiOperationDocumentation(new CatalogLocaleBundlePolicy(new LocaleCatalogLoader(null).load()));
        var json = io.swagger.v3.core.util.Json31.mapper();
        var source = json.createObjectNode().put("description", "Provider-specific recovery.");
        var operation = new Operation().description("Submit.");
        docs.finishDetails(operation, java.util.Map.of("sample", source, "undocumented", json.createObjectNode()), java.util.Locale.US);
        assertThat(operation.getDescription()).isEqualTo("Submit.\n\n### Details\n\n#### `sample`\n\nProvider-specific recovery.");
        var withoutProvider = new Operation().description("Submit.");
        docs.finishDetails(withoutProvider, java.util.Map.of(), java.util.Locale.US);
        assertThat(withoutProvider.getDescription()).isEqualTo("Submit.");
    }

    @Test
    void relatesInstalledResourceRoutesWithoutMatchingGenericTaskPaths() {
        var paths = new io.swagger.v3.oas.models.Paths();
        var start = new Operation().description("See POST /api/enrichment/run or GET /api/absent.");
        paths.addPathItem("/api/widgets/tasks", new io.swagger.v3.oas.models.PathItem().post(start));
        paths.addPathItem("/api/widgets/presets", new io.swagger.v3.oas.models.PathItem().get(new Operation()));
        paths.addPathItem("/api/widgets/tasks/{id}", new io.swagger.v3.oas.models.PathItem().get(new Operation()));
        paths.addPathItem("/api/other/tasks/{id}", new io.swagger.v3.oas.models.PathItem().get(new Operation()));
        paths.addPathItem("/api/enrichment/run", new io.swagger.v3.oas.models.PathItem().post(new Operation()
                .addTagsItem("Other group").summary("Run [enrichment]").externalDocs(
                new io.swagger.v3.oas.models.ExternalDocumentation().url("https://example.org/enrichment"))));
        RelatedOperations.describe(paths);
        assertThat(start.getDescription()).isEqualTo("See POST /api/enrichment/run or GET /api/absent.");
        assertThat(start.getExtensions().get("x-related-guides")).isEqualTo(java.util.List.of("https://example.org/enrichment"));
        assertThat(start.getExtensions().get("x-related-operations")).isEqualTo(java.util.List.of("POST /api/enrichment/run", "GET /api/widgets/tasks/{id}", "GET /api/widgets/presets"));
    }

    @Test
    void findsOperationsInTheSameGroupForAnIsolatedRoute() {
        var paths = new io.swagger.v3.oas.models.Paths();
        var source = new Operation().addTagsItem("jobs");
        paths.addPathItem("/api/cancel/{artworkId}", new io.swagger.v3.oas.models.PathItem().post(source));
        paths.addPathItem("/api/download/status/{artworkId}", new io.swagger.v3.oas.models.PathItem().get(new Operation().addTagsItem("jobs")));
        paths.addPathItem("/api/preferences", new io.swagger.v3.oas.models.PathItem().get(new Operation().addTagsItem("settings")));
        RelatedOperations.describe(paths);
        assertThat(source.getExtensions().get("x-related-operations")).isEqualTo(java.util.List.of("GET /api/download/status/{artworkId}"));
    }

    @Test
    void keepsExplicitIdsAndDistinguishesRoutesWithIdenticalCamelCaseNames() {
        var paths = new io.swagger.v3.oas.models.Paths();
        var grouped = new Operation();
        var nested = new Operation();
        var declared = new Operation();
        declared.addExtension(ApiOperationDocumentation.EXPLICIT_ID, "getFooBar");
        paths.addPathItem("/api/foo-bar", new io.swagger.v3.oas.models.PathItem().get(grouped));
        paths.addPathItem("/api/foo/bar", new io.swagger.v3.oas.models.PathItem().get(nested));
        paths.addPathItem("/api/declared", new io.swagger.v3.oas.models.PathItem().post(declared));
        ApiReferenceConfiguration.assignOperationIds(paths);
        assertThat(declared.getOperationId()).isEqualTo("getFooBar");
        assertThat(grouped.getOperationId()).startsWith("getFooBarRoute");
        assertThat(nested.getOperationId()).startsWith("getFooBarRoute").isNotEqualTo(grouped.getOperationId());
    }

    @Test
    void aRangeHeaderOnAJsonOperationDoesNotDeclareByteRanges() {
        var operation = new Operation().responses(new ApiResponses().addApiResponse("200", response(new ObjectSchema())))
                .addParametersItem(new io.swagger.v3.oas.models.parameters.HeaderParameter().name("Range"));
        HttpBindingDocumentation.describeFileRanges(operation, key -> key);
        assertThat(operation.getResponses()).containsOnlyKeys("200");
        assertThat(operation.getResponses().get("200").getHeaders()).isNullOrEmpty();
    }

    private static ApiResponse response(Schema<?> schema) {
        return new ApiResponse().content(new Content().addMediaType("application/json", new MediaType().schema(schema)));
    }
}
