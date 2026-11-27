package acceptance;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.common.PixivConnectivityProbe;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.gui.SettingsApiService;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;

import static acceptance.OpenApiGetProbe.validator;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Run in a separate JVM with the app classpath and the compiled reactor root as its only argument. */
public class OpenApiResponseAcceptance {
    private static final JsonMapper JSON = JsonMapper.shared();

    @TestConfiguration(proxyBeanMethods = false)
    public static class CatalogFixture {
        @Bean @Primary PluginRepositoryRegistry fixtureRepositories(PluginCatalogProperties properties) {
            return spy(new PluginRepositoryRegistry(properties));
        }

        @Bean @Primary PixivConnectivityProbe fixtureProbe() {
            return mock(PixivConnectivityProbe.class);
        }

        @Bean @Primary PluginCatalogService fixtureCatalog() {
            var catalog = mock(PluginCatalogService.class);
            var entry = JSON.readValue("""
                    {"pluginId":"contract-fixture","market":{"recommended":false,"officialRequired":false,"defaultInstalled":false},
                     "packages":[{"version":"1.0.0","packageUrl":"https://fixture.invalid/package.zip","expectedSizeBytes":1,
                                  "sha256":"%s","deprecated":false}]}
                    """.formatted("0".repeat(64)), PluginCatalogEntry.class);
            when(catalog.loadPage(any(PluginRepository.class), any()))
                    .thenReturn(new PluginCatalogPage("fixture", List.of(entry), null, 1L, Map.of(), false));
            return catalog;
        }
    }

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        Path repo = Path.of(args[0]).toRealPath();
        Path root = Files.createTempDirectory(repo.resolve("target"), "openapi-responses-");
        for (String dir : List.of("config", "state", "data", "instance", "plugins"))
            System.setProperty("pixivdownload." + dir + "-dir", root.resolve(dir).toString());
        System.setProperty("update.enabled", "false");
        System.setProperty("pixivdownload.plugin-dev.enabled", "true");
        System.setProperty("pixivdownload.plugin-dev.root", repo.toString());
        Path config = root.resolve("config/config.yaml");
        Files.createDirectories(config.getParent());
        String downloadTemplate = root.resolve(DownloadConfig.DEFAULT_TEMPLATE).toString();
        new ConfigFileEditor(config).write("download.root-folder", downloadTemplate);
        try (var app = PixivDownloadApplication.start(new String[]{"--server.address=127.0.0.1", "--server.port=0",
                "--spring.config.import=optional:" + config.toUri(), "--download.root-folder=" + downloadTemplate,
                "--setup.browser.auto-open=false", "--update.enabled=false", "--update.auto-check=false",
                "--spring.main.sources=" + CatalogFixture.class.getName(), "--plugin-catalog.manifest-url=https://fixture.invalid/catalog.json"});
             var client = HttpClient.newHttpClient()) {
            if (!Path.of(app.getBean(DownloadConfig.class).getRootFolder()).toAbsolutePath().normalize().startsWith(root))
                throw new AssertionError("Expected archive and artwork paths inside the test directory");
            var setup = app.getBean(SetupService.class);
            setup.init("contract-test", UUID.randomUUID().toString(), "solo");
            var base = URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort());
            var request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(90))
                    .header("X-Session-Token", setup.createSession(true));
            var imports = app.getBean(LocalArtworkImports.class);
            Path files = Files.createDirectory(root.resolve("images"));
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", files.resolve("42_p0.png").toFile());
            String template = files.toString().replace('\\', '/') + "/{artwork_id}_p{page}.{extension}";
            var scan = imports.scan(new LocalArtworkImports.Request(List.of(template), List.of(42L), true, ""));
            await(imports, scan.id(), LocalArtworkImports.Phase.SCANNING, LocalArtworkImports.Phase.READY);
            imports.start(scan.id());
            if (await(imports, scan.id(), LocalArtworkImports.Phase.IMPORTING, LocalArtworkImports.Phase.COMPLETED).imported() != 1)
                throw new AssertionError("Fixture artwork was not imported");

            JsonNode spec = get(client, request.uri(base.resolve("/api/openapi.json?lang=en-US")));
            Files.writeString(root.resolve("openapi-en.json"), spec.toString());
            OpenApiGetProbe.validate(spec, "/api/openapi.json", "administrator", spec);
            verifyClientContracts(spec);
            verifyHttpInputs(client, request, base, spec);
            verifyContractBoundaries(client, request, base, spec);
            verifyConditionalRequests(spec);
            verifyArchiveNavigation(client, request, base, spec);
            verifyProgramDenials(client, request, base, spec);
            for (String format : List.of("bmp", "png,bmp")) {
                var invalid = client.send(request.copy().uri(base.resolve("/api/gallery/artworks?format=" + format)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (invalid.statusCode() != 400) throw new AssertionError("Invalid gallery filter returned " + invalid.statusCode());
            }
            verifyDownloadOperations(client, request, base, spec);
            verifyRejectedSubmission(client, request, base);
            verifyDetailsPage(repo, base, spec, setup.createSession(true));
            verifyOutputReceipt(client, request, base, app.getBean(DownloadLifecycleRegistry.class), root.resolve("output"));
            System.out.println("Download operation HTTP contracts passed");
            var check = get(client, request.uri(base.resolve("/api/admin/update/check")));
            if (check.required("enabled").asBoolean()) throw new AssertionError("Update fixture must be offline");
            var db = new JdbcTemplate(app.getBean(DataSource.class));
            verifyResponseNullability(client, request, base, spec, db);
            var gui = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                    .header(GuiTokenProvider.HEADER_NAME, app.getBean(GuiTokenProvider.class).getToken());
            for (String channel : List.of("beta", "NIGHTLY")) {
                for (String api : List.of("gui", "admin")) {
                    var identity = api.equals("gui") ? gui : request;
                    var rejected = post(client, identity, base, "/api/" + api + "/update/download?channel=" + channel, Map.of());
                    if (rejected.statusCode() != 400) throw new AssertionError(api + " accepted channel " + channel + ": " + rejected.statusCode());
                }
            }
            OpenApiGetProbe.validate(spec, "/api/gui/plugins/status", "GUI", get(client, gui.uri(base.resolve("/api/gui/plugins/status"))));
            var probe = app.getBean(PixivConnectivityProbe.class);
            for (var sample : List.of(new PixivConnectivityProbe.Result(true, 200, 10, null),
                    new PixivConnectivityProbe.Result(false, 503, 10, null),
                    new PixivConnectivityProbe.Result(false, 0, 10, "timeout"))) {
                when(probe.probe()).thenReturn(sample);
                verifyConnectivityResponses(client, request, gui, base, spec, sample);
            }
            verifyTaskStates(client, request, base, spec, root, db);
            doReturn(false).when(app.getBean(PluginRepositoryRegistry.class)).featureEnabled();
            var updates = validatedGet(client, request, base, spec, "/api/plugins/updates");
            if (updates.required("enabled").asBoolean() || !updates.required("checkedAt").isNull())
                throw new AssertionError("Expected a disabled market with no update check timestamp");
            verifyMetadataFailures(client, request, base, app.getBean("pixivCredentialRestTemplate", RestTemplate.class));
            new ArtworkRecoveryAcceptance(client, request, base, app).verify(app, repo);
            new NovelOwnershipAcceptance(client, request, base, app).verify(app);
            new DirectoryHashRecoveryAcceptance(client, request, base, app).verify(app);
            System.out.println("OpenAPI response acceptance passed");
        }
    }

    private static void verifyConnectivityResponses(HttpClient client, HttpRequest.Builder admin, HttpRequest.Builder gui,
                                                    URI base, JsonNode spec, PixivConnectivityProbe.Result expected) throws Exception {
        for (String path : List.of("/api/onboarding/connectivity", "/api/gui/pixiv-connectivity", "/api/admin/system/connectivity")) {
            JsonNode response;
            if (path.equals("/api/admin/system/connectivity")) {
                var result = post(client, admin, base, path, Map.of());
                if (result.statusCode() != 200) throw new AssertionError(path + " returned " + result.statusCode());
                response = JSON.readTree(result.body());
                var schema = spec.required("paths").required(path).requiredAt("/post/responses/200/content/application~1json/schema");
                if (!validator(spec, schema).validate(response).isEmpty()) throw new AssertionError("Invalid response for " + path);
            } else {
                response = validatedGet(client, path.startsWith("/api/gui/") ? gui : admin, base, spec, path);
            }
            if (response.required("reachable").asBoolean() != expected.reachable()
                    || !response.required("errorType").equals(JSON.valueToTree(expected.errorType())))
                throw new AssertionError("Unexpected connectivity result on " + path + ": " + response);
        }
    }

    private static void verifyResponseNullability(HttpClient client, HttpRequest.Builder admin, URI base,
                                                  JsonNode spec, JdbcTemplate db) throws Exception {
        db.update("INSERT INTO tags(tag_id, name) VALUES (771, 'untranslated')");
        db.update("INSERT INTO artwork_tags(artwork_id, tag_id) VALUES (42, 771)");
        db.update("INSERT INTO novels(novel_id, title, folder, count, extensions, time) VALUES (42, 'Novel', 'fixture', 1, 'txt', 1)");
        db.update("INSERT INTO novel_tags(novel_id, tag_id) VALUES (42, 771)");
        db.update("INSERT INTO guest_invites(id, code, name, tag_unrestricted, created_time) VALUES (42, 'invite', 'Fixture', 0, 1)");
        db.update("INSERT INTO guest_invite_tags(invite_id, tag_id) VALUES (42, 771)");
        db.update("INSERT INTO notification_messages(id, category, severity, title, body, created_time) VALUES ('42', 'system', 'INFO', 'Fixture', 'Unread', 1)");

        var models = spec.at("/components/schemas");
        var tag = models.required(WorkTag.class.getCanonicalName());
        var tagValidator = validator(spec, tag);
        if (!tagValidator.validate(JSON.valueToTree(new WorkTag(null, "tag", null))).isEmpty()
                || tagValidator.validate(JSON.createObjectNode().put("tagId", "invalid").put("name", "tag")).isEmpty())
            throw new AssertionError("Expected an integer or null for the local tag ID");
        var preset = new SettingsApiService.Preset(null, null, 0, "server", "local", null, "label", "", Map.of());
        if (!validator(spec, models.required(SettingsApiService.Preset.class.getCanonicalName())).validate(JSON.valueToTree(preset)).isEmpty())
            throw new AssertionError("Core preset with no namespace violates its response schema");

        // Validate the specific response model. The generic 409 alternative also accepts path-action bodies.
        var samples = JSON.readTree("""
                {
                  "gui.controller.GuiStatusController.GuiSetupInitResponse": [
                    {"success":true,"mode":"solo","error":null}, {"success":false,"mode":null,"error":"invalid"}
                  ],
                  "gui.controller.GuiStatusController.GuiChangePasswordResponse": [{"success":true,"error":null}],
                  "gui.controller.GuiStatusController.GuiLogoutAllResponse": [{"success":true,"code":null,"error":null}],
                  "ai.controller.AiModelsResponse": [{"success":true,"models":[],"count":0,"error":null,"code":null}],
                  "mail.controller.MailTestResponse": [{"success":true,"error":null}],
                  "plugin.catalog.trust.PluginCatalogRevocationService.RefreshResult": [{"refreshed":true,"code":null}],
                  "novel.controller.NovelDownloadController.TranslateSeriesTitleResponse": [
                    {"langCode":null,"title":null,"description":null,"message":null,"variantKey":null}
                  ],
                  "download.controller.DownloadTaskController.PathActionResponse": [
                    {"code":"DOWNLOAD_PATH_ACTION_REQUIRED","error":"Choose a path","pathProblem":null}
                  ],
                  "novel.controller.NovelDownloadController.PathActionResponse": [
                    {"code":"DOWNLOAD_PATH_ACTION_REQUIRED","error":"Choose a path","pathProblem":null,"retryToken":null}
                  ]
                }
                """);
        for (var entry : samples.properties()) {
            var schema = validator(spec, models.required("top.sywyar.pixivdownload." + entry.getKey()));
            for (var sample : entry.getValue()) {
                var errors = schema.validate(sample);
                if (!errors.isEmpty()) throw new AssertionError(entry.getKey() + ": " + errors);
            }
        }

        var nullableFields = Map.ofEntries(
                Map.entry("/api/plugins/status", List.of("/plugins/0/verification/revocation")),
                Map.entry("/api/gallery/artworks", List.of("/content/0/authorName", "/content/0/tags/0/translatedName")),
                Map.entry("/api/gallery/artwork/{artworkId}", List.of("/tags/0/translatedName")),
                Map.entry("/api/gallery/artwork/{artworkId}/series", List.of("/prev", "/next")),
                Map.entry("/api/gallery/tags", List.of("/tags/0/translatedName")),
                Map.entry("/api/admin/invites/{id}", List.of("/tags/0/translatedName")),
                Map.entry("/api/stats/dashboard", List.of("/topTags/0/translatedName")),
                Map.entry("/api/gallery/novels", List.of("/content/0/tags/0/translatedName")),
                Map.entry("/api/gallery/novel/{novelId}", List.of("/tags/0/translatedName")),
                Map.entry("/api/gallery/novels/tags", List.of("/tags/0/translatedName")),
                Map.entry("/api/library/artworks/{artworkId}", List.of("/moveFolder", "/moveTime", "/seriesId", "/seriesOrder", "/seriesTitle")),
                Map.entry("/api/downloaded/history/paged", List.of("/content/0/moveFolder", "/content/0/seriesId")),
                Map.entry("/api/downloaded/{artworkId}", List.of("/tags/0/translatedName")),
                Map.entry("/api/notifications/{id}", List.of("/scenarioId", "/actionUrl", "/contentUrl", "/embeddedContentUrl", "/readTime")),
                Map.entry("/api/tools/path-prefixes", List.of("/symbolicRoot/suggestedOldPath")),
                Map.entry("/api/duplicates/groups", List.of("/scan/startedTime")),
                Map.entry("/api/duplicates/scan/status", List.of("/startedTime")),
                Map.entry("/api/tools/startup-cache", List.of("/task")),
                Map.entry("/api/admin/update/last", List.of("/nightlyAlternative")));
        for (var entry : nullableFields.entrySet()) {
            var body = validatedGet(client, admin, base, spec, entry.getKey());
            for (String pointer : entry.getValue()) {
                if (!body.at(pointer).isNull()) throw new AssertionError("Expected JSON null at " + entry.getKey() + " " + pointer);
            }
        }
        for (var route : List.of("/api/notifications", "/api/download/extensions", "/api/download/tasks", "/api/download/tasks/history", "/api/settings/presets"))
            validatedGet(client, admin, base, spec, route);
        var fields = validatedGet(client, admin, base, spec, "/api/settings");
        if (fields.valueStream().noneMatch(field -> field.path("namespace").isNull()))
            throw new AssertionError("Expected a core setting without a namespace");
        var sources = validatedGet(client, admin, base, spec, "/api/schedule/sources").required("sources");
        if (sources.valueStream().noneMatch(source -> source.path("frontend").isNull()))
            throw new AssertionError("Expected a schedule source without a frontend");

        db.update("UPDATE artworks SET series_id = 0, series_order = 0 WHERE artwork_id = 42");
        var standalone = validatedGet(client, admin, base, spec, "/api/library/artworks/{artworkId}");
        for (String field : List.of("seriesId", "seriesOrder", "seriesTitle"))
            if (!standalone.required(field).isNull()) throw new AssertionError("Standalone artwork has " + field);

        db.update("INSERT INTO manga_series(series_id, title, updated_time) VALUES (321, 'Fixture series', 1)");
        db.update("UPDATE artworks SET series_id = 321, series_order = 2 WHERE artwork_id = 42");
        for (String route : List.of("/api/gallery/artwork/{artworkId}", "/api/library/artworks/{artworkId}", "/api/downloaded/history/paged")) {
            var body = validatedGet(client, admin, base, spec, route);
            var work = body.has("content") ? body.at("/content/0") : body;
            if (work.required("seriesId").asLong() != 321 || work.required("seriesOrder").asLong() != 2
                    || !"Fixture series".equals(work.required("seriesTitle").asString()))
                throw new AssertionError("Lost stored series metadata at " + route);
        }
    }

    private static void verifyTaskStates(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec, Path root,
                                         JdbcTemplate db) throws Exception {
        var sources = validatedGet(client, admin, base, spec, "/api/schedule/sources").required("sources");
        String batchPath = "/api/download/batches";
        var batch = (tools.jackson.databind.node.ObjectNode) spec.required("paths").required(batchPath)
                .requiredAt("/post/requestBody/content/application~1json/examples/explicit-list/value").deepCopy();
        var source = sources.valueStream().filter(s -> s.required("sourceType").asString().equals("explicit-list"))
                .findFirst().orElseThrow();
        batch.set("activationToken", source.required("activationToken"));
        var invalid = batch.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) invalid.requiredAt("/definition/source")).remove("acquisitionMode");
        var rejected = post(client, admin, base, batchPath, invalid);
        expectError(rejected, 400, "INVALID_TOOL_REQUEST", null);
        var failure = JSON.readTree(rejected.body());
        if (!"/definition/source/acquisitionMode".equals(failure.required("field").asString())
                || !"required".equals(failure.required("reason").asString())) throw new AssertionError(failure);
        var accepted = post(client, admin, base, batchPath, batch);
        if (accepted.statusCode() != 202) throw new AssertionError("Fixture batch returned " + accepted.statusCode());
        String itemsUrl = accepted.headers().firstValue("Location").orElseThrow() + "/items";
        String itemsPath = batchPath + "/{id}/items";
        verifyBatchItemPagination(client, admin, base.resolve(itemsUrl), spec.required("paths").required(itemsPath).required("get"));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            var items = validatedGet(client, admin, base, spec, itemsPath, itemsUrl).required("items");
            var skipped = items.valueStream().filter(row -> "skipped-downloaded".equals(row.path("status").asString(""))).findFirst();
            if (skipped.isPresent()) {
                var result = skipped.get().required("result");
                if (!"ALREADY_COMPLETED".equals(result.required("outcome").asString())
                        || !"work.already-completed".equals(result.required("resultCode").asString())) throw new AssertionError(result);
                break;
            }
            if (System.nanoTime() >= deadline) throw new AssertionError("Imported fixture work missing from batch");
            Thread.sleep(100);
        }

        String schedulePath = "/api/schedule/tasks";
        var scheduledSource = sources.valueStream().filter(s -> s.required("sourceType").asString().equals("user-new"))
                .findFirst().orElseThrow();
        var createRequest = (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(Map.of("sourceType", "user-new", "name", "fixture",
                "activationToken", scheduledSource.required("activationToken").asString(), "triggerKind", "cron",
                "cronExpr", "0 0 0 1 1 *", "definitionJson", "{\"kind\":\"illust\",\"source\":{\"userId\":\"42\"}}"));
        var created = post(client, admin, base, schedulePath, createRequest.putNull("expectedStateVersion"));
        if (created.statusCode() != 200) throw new AssertionError("Fixture schedule returned " + created.statusCode());
        long id = JSON.readTree(created.body()).required("id").asLong();
        // Persist the state of a disabled task whose original source provider is no longer installed.
        db.update("UPDATE scheduled_tasks SET enabled=0, next_run_time=NULL, source_owner_plugin_id=? WHERE id=?", "absent-provider", id);
        var task = validatedGet(client, admin, base, spec, schedulePath + "/{id}", schedulePath + "/" + id);
        for (String field : List.of("intervalMinutes", "nextRunTime", "sourceActivationToken", "proxy"))
            if (!task.required(field).isNull()) throw new AssertionError("Expected absent " + field + " on disabled cron task");

        for (String tool : List.of("file-import", "media")) {
            String path = "/api/tools/" + tool;
            var owner = validatedGet(client, admin, base, spec, path).required(0).required("identity");
            String query = "?pluginId=" + owner.required("pluginId").asString() + "&packageId=" + owner.required("packageId").asString()
                    + "&generation=" + owner.required("generation").asLong() + "&publication=" + owner.required("publication").asLong();
            String current = path + "/tasks/current";
            if (!validatedGet(client, admin, base, spec, current, current + query).required("executionId").isNull())
                throw new AssertionError("Idle tool has an execution ID: " + tool);
        }

        // A paused job can be read before a translation provider has identified the source language.
        UUID jobId = UUID.randomUUID();
        var selection = Map.of("novelId", 42, "targetLanguage", "en", "segmentSize", 500, "overwrite", false,
                "translateBody", true, "translateTitle", false, "translateDescription", false);
        var saved = Map.of("id", jobId, "engine", "fixture", "state", "PAUSED", "reason", "translation.interrupted",
                "novelIds", List.of(42), "startedAt", 1, "updatedAt", 2, "submission", Map.of("requestId", jobId,
                        "allChapters", false, "mergeAfter", false, "metadataOnly", false, "request", selection));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/translation/plugin.db"));
             var insert = connection.prepareStatement("INSERT INTO translation_jobs(id,payload) VALUES(?,?)")) {
            insert.setString(1, jobId.toString());
            insert.setString(2, JSON.writeValueAsString(saved));
            insert.executeUpdate();
            UUID completedId = UUID.randomUUID();
            var completed = (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(saved);
            completed.put("id", completedId.toString()).put("state", "COMPLETED").put("outcome", "EMPTY").put("startedAt", 3);
            completed.remove("reason");
            insert.setString(1, completedId.toString());
            insert.setString(2, completed.toString());
            insert.executeUpdate();
        }
        for (String path : List.of("/api/novel/translate-status/{novelId}", "/api/download/novel/translate-status/{novelId}")) {
            var state = validatedGet(client, admin, base, spec, path, path.replace("{novelId}", "42"));
            if (!"DONE".equals(state.required("phase").asString()) || !state.required("langCode").isNull()
                    || !state.required("failureReason").isNull()) throw new AssertionError(state);
        }
        var job = validatedGet(client, admin, base, spec, "/api/translation/jobs/{id}", "/api/translation/jobs/" + jobId);
        for (String field : List.of("language", "variantKey", "runId", "outcome"))
            if (!job.required(field).isNull()) throw new AssertionError("Unexpected paused-job " + field);
    }

    private static JsonNode validatedGet(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec,
                                         String path) throws Exception {
        String url = path.replace("{artworkId}", "42").replace("{novelId}", "42").replace("{id}", "42");
        return validatedGet(client, admin, base, spec, path, url);
    }

    private static JsonNode validatedGet(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec,
                                         String path, String url) throws Exception {
        var response = get(client, admin.copy().uri(base.resolve(url)));
        OpenApiGetProbe.validate(spec, path, "administrator", response);
        return response;
    }

    private static void verifyArchiveNavigation(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec) throws Exception {
        var paths = spec.required("paths");
        for (var route : Map.of("/api/gallery/artworks/export", "200", "/api/gallery/novels/export", "200",
                "/api/quota/pack", "200", "/api/archive/pack-artworks", "200", "/api/download/pixiv", "429",
                "/api/download/pixiv/novel", "429", "/api/novel/download", "429").entrySet()) {
            var operation = paths.required(route.getKey()).required("post");
            var links = operation.required("responses").required(route.getValue()).required("links");
            for (var follow : Map.of("inspectArchive", "status", "downloadArchive", "download").entrySet()) {
                String target = "/api/archive/" + follow.getValue() + "/{token}";
                var link = links.required(follow.getKey());
                var linked = spec.requiredAt(URI.create(link.required("operationRef").asString()).getFragment());
                if (!linked.equals(paths.required(target).required("get"))
                        || !"$response.body#/archiveToken".equals(link.requiredAt("/parameters/token").asString())
                        || link.required("description").asString().isBlank()
                        || operation.required("x-related-operations").valueStream().noneMatch(v -> ("GET " + target).equals(v.asString())))
                    throw new AssertionError("Invalid archive transition from " + route.getKey() + " to " + target);
            }
        }
        for (String library : List.of("artworks", "novels")) {
            String exportPath = "/api/gallery/" + library + "/export";
            String formatCode = "validation.archive.export.format.unsupported";
            var invalidFormat = post(client, admin, base, exportPath, Map.of("mode", "ids", "ids", List.of(), "format", " RAR "));
            expectError(invalidFormat, 400, formatCode, null);
            if (!JSON.readTree(invalidFormat.body()).required("error").asString().contains("rar"))
                throw new AssertionError("Missing normalized format in the error for " + exportPath);
            if (!declaresCode(spec, paths.required(exportPath).requiredAt("/post/responses/400/content/application~1json/schema"), formatCode))
                throw new AssertionError("Missing unsupported format error on " + exportPath);
            String delete = "/api/gallery/" + library + "/delete";
            // Exclude the only imported work so both requests have an empty effective selection.
            var selection = new java.util.HashMap<String, Object>(Map.of(
                    "mode", "filter", "filter", Map.of("page", 8, "size", 1), "excludeIds", List.of(42)));
            expectError(post(client, admin, base, delete, selection), 400, "work.selection.confirm-all", null);
            var destructiveExport = new java.util.HashMap<>(selection);
            destructiveExport.put("deleteAfter", true);
            expectError(post(client, admin, base, "/api/gallery/" + library + "/export", destructiveExport),
                    400, "work.selection.confirm-all", null);
            selection.put("confirmAll", true);
            var confirmed = post(client, admin, base, delete, selection);
            if (confirmed.statusCode() != 200 || JSON.readTree(confirmed.body()).required("deleted").asInt() != 0)
                throw new AssertionError("Confirmed empty selection failed for " + library);
        }
        var export = post(client, admin, base, "/api/gallery/artworks/export", Map.of("mode", "ids", "ids", List.of(42)));
        if (export.statusCode() != 200) throw new AssertionError("Fixture export returned " + export.statusCode());
        String token = JSON.readTree(export.body()).required("archiveToken").asString();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            String status = get(client, admin.copy().uri(base.resolve("/api/archive/status/" + token))).required("status").asString();
            if ("ready".equals(status)) {
                var response = client.send(admin.copy().uri(base.resolve("/api/archive/download/" + token)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.body()))) {
                    if (response.statusCode() != 200 || zip.getNextEntry() == null)
                        throw new AssertionError("Archive download did not return a ZIP entry");
                }
                return;
            }
            if (!List.of("pending", "creating").contains(status)) throw new AssertionError("Fixture export ended " + status);
            Thread.sleep(50);
        }
        throw new AssertionError("Fixture archive was not ready");
    }

    private static void verifyDetailsPage(Path repo, URI base, JsonNode spec, String session) throws Exception {
        String cookie = spec.at("/components/securitySchemes/adminSessionCookie/name").asString();
        var script = repo.resolve("pixivdownload-app/src/test/resources/openapi/details-browser.cjs");
        var browser = new ProcessBuilder("node", script.toString(), base.toString(), cookie)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            try (var input = browser.getOutputStream()) {
                input.write(session.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (!browser.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) || browser.exitValue() != 0)
                throw new AssertionError("OpenAPI details page acceptance failed");
        } finally {
            if (browser.isAlive()) browser.destroyForcibly();
        }
    }

    private static void verifyDownloadOperations(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec) throws Exception {
        var page = client.send(HttpRequest.newBuilder(base.resolve("/openapi?lang=en-US")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (page.statusCode() != 200 || !page.body().contains(">Open API reference</button>"))
            throw new AssertionError("Program key form did not render its localized submit button");
        var paths = spec.required("paths");
        paths.required("/api/pixiv/user/{userId}/downloads").required("post").required("responses").required("202");
        paths.required("/api/library/artworks/{artworkId}/partial").required("get").required("responses").required("200");
        for (String route : List.of("/api/downloaded/{artworkId}/recover-metadata", "/api/library/artworks/{artworkId}/recover-metadata")) {
            var operation = paths.required(route).required("post");
            if (!operation.requiredAt("/responses/400/description").asString().contains("directory-template.occupied"))
                throw new AssertionError("Missing directory ownership conflict on " + route);
            operation.requiredAt("/responses/400/content/application~1json/schema");
        }
        var artworkSubmission = spec.required("x-download-submissions").required("artwork");
        var sample = paths.required("/api/download/tasks").requiredAt("/post/responses/202/content/application~1json/example/task");
        var types = get(client, admin.copy().uri(base.resolve("/api/download/extensions"))).required("downloadTypes");
        boolean matchingQueue = types.valueStream().anyMatch(type -> type.required("type").equals(sample.required("queueType"))
                && artworkSubmission.required("x-program-permission").asString().equals(type.requiredAt("/owner/pluginId").asString() + ":downloads.create"));
        if (!matchingQueue || !"artwork".equals(sample.requiredAt("/attempt/workType").asString())
                || !"QUEUED".equals(sample.required("phase").asString()) || sample.required("revision").asLong() <= 1
                || sample.required("outputPath").asString("").isBlank())
            throw new AssertionError("Expected the queued artwork example to include its published queue, revision and output directory");
        var options = artworkSubmission.at("/properties/options/properties");
        String pageHelp = options.required("pages").required("description").asString();
        if (!pageHelp.contains("omit pages to download the entire artwork"))
            throw new AssertionError("Missing full artwork recommendation in pages documentation");
        var chinese = get(client, admin.copy().uri(base.resolve("/api/openapi.json?lang=zh-CN")));
        String field = "/components/schemas/DownloadSubmission.artwork/properties/options/properties/pages/description";
        if (!chinese.at(field).asString("").contains("不推荐指定页码，非必要时请省略 pages，下载完整作品"))
            throw new AssertionError("Missing recommendation in Chinese artwork submission schema");
        for (var locale : Map.of("en-US", "Details", "zh-CN", "详细信息", "zh-Hant", "詳細資訊",
                "ja-JP", "詳細情報", "ko-KR", "상세 정보").entrySet()) {
            var document = switch (locale.getKey()) {
                case "en-US" -> spec;
                case "zh-CN" -> chinese;
                default -> get(client, admin.copy().uri(base.resolve("/api/openapi.json?lang=" + locale.getKey())));
            };
            var operations = document.required("paths");
            var cancel = operations.required("/api/tools/ffmpeg/tasks/{executionId}/cancel").required("post");
            String repairColumn = document.requiredAt("/components/schemas/top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.FolderArtwork/properties/moved/description").asString();
            if (!repairColumn.contains("move_folder") || !repairColumn.contains("true") || !repairColumn.contains("false"))
                throw new AssertionError("Missing directory repair column contract: " + locale.getKey());
            var pinFailure = document.required("paths").required("/api/tools/path-prefixes/pin").requiredAt("/post/responses/500");
            if (!pinFailure.required("description").asString().contains("PATH_PREFIX_WRITE_FAILED")
                    || !declaresCode(document, pinFailure.requiredAt("/content/application~1json/schema"), "PATH_PREFIX_WRITE_FAILED"))
                throw new AssertionError("Missing symbolic root rollback contract: " + locale.getKey());
            if (!"core:ffmpeg.cancel".equals(cancel.required("x-program-permission").asString()))
                throw new AssertionError("FFmpeg cancellation must require the core owner's cancellation grant");
            for (String status : List.of("200", "202", "404", "409")) cancel.required("responses").required(status);
            verifyRetiredClassifier(document);
            verifyComparisonExamples(document);
            verifyIntegrationExamples(document);
            verifyDownloadEventContract(document);
            for (String endpoint : List.of("rawfile", "image")) {
                var operation = operations.required("/api/downloaded/" + endpoint + "/{artworkId}/{page}").required("get");
                var headers = operation.required("parameters").valueStream()
                        .filter(param -> "header".equals(param.path("in").asString("")))
                        .map(param -> param.required("name").asString()).toList();
                if (!headers.containsAll(List.of("Range", "If-None-Match")) || headers.contains("If-Modified-Since"))
                    throw new AssertionError("Incorrect file request validators on " + endpoint);
                var responses = operation.required("responses");
                for (String status : List.of("200", "206", "304", "416")) {
                    var declared = responses.required(status).required("headers");
                    declared.required("ETag");
                    if (declared.has("Last-Modified")) throw new AssertionError("Unexpected date validator on " + endpoint);
                }
            }
            var metadata = document.required("paths").required("/api/pixiv/user/{userId}/meta/schedule").required("post");
            if (!"core:schedule.sources.read".equals(metadata.at("/x-program-permissions/discovery/permission").asString())
                    || !metadata.required("x-related-operations").valueStream().anyMatch(v -> "GET /api/schedule/sources".equals(v.asString())))
                throw new AssertionError("Missing scheduled author metadata discovery instructions");
            if (!metadata.at("/x-program-permissions/submit").valueStream().anyMatch(grant ->
                    "request#/taskId".equals(grant.at("/when/keyFrom").asString(""))
                    && "task#/sourceOwnerPluginId".equals(grant.path("ownerFrom").asString(""))
                    && "schedule.manage".equals(grant.path("permissionId").asString(""))))
                throw new AssertionError("Missing task owner grant for scheduled author metadata");
            verifyPermissionStages(document);
            verifyOperationDetails(document, locale.getValue());
            verifyBatchAndSearchDocs(document);
            String pagination = document.requiredAt("/info/description").asString();
            if (!pagination.contains("`nextCursor`") || !pagination.contains("`nextVersionCursor`"))
                throw new AssertionError("Missing market pagination cursors for " + locale.getKey());
            for (var entry : Map.of("/api/download/tasks", "post", "/api/pixiv/artwork/{artworkId}/meta", "get",
                    "/api/pixiv/novel/{novelId}/meta", "get").entrySet()) {
                var response = operations.required(entry.getKey()).required(entry.getValue()).at("/responses/404");
                if (!response.path("description").asString("").contains("WORK_NOT_FOUND")
                        || !response.required("content").toString().contains("WORK_NOT_FOUND"))
                    throw new AssertionError("Missing work lookup failure contract for " + entry.getKey());
            }
            var limited = operations.required("/api/download/tasks").requiredAt("/post/responses/429");
            if (!limited.path("description").asString("").contains("DOWNLOAD_TASK_UPSTREAM_RATE_LIMITED")
                    || !"int64".equals(limited.at("/headers/Retry-After/schema/format").asString(""))
                    || !limited.required("content").toString().contains("DOWNLOAD_TASK_UPSTREAM_RATE_LIMITED"))
                throw new AssertionError("Missing submission rate-limit contract");
            var batchCode = document.at("/components/schemas/top.sywyar.pixivdownload.schedule.batch.ManualBatchService.View/properties/code");
            var batch = document.at("/components/schemas/top.sywyar.pixivdownload.schedule.batch.ManualBatchService.View/properties");
            var delay = batch.required("retryAfterMillis");
            var state = batch.required("state");
            if (state.has("$ref")) state = document.at(state.required("$ref").asString().substring(1));
            if (!"int64".equals(delay.path("format").asString()) || delay.path("description").asString("").isBlank()
                    || !state.required("enum").valueStream().anyMatch(v -> "RETRY_LATER".equals(v.asString())))
                throw new AssertionError("Missing manual batch retry state or delay");
            String codeDescription = batchCode.path("description").asString("");
            if (batchCode.has("enum") || !batchCode.path("examples").isArray()
                    || !codeDescription.contains("batch.work-failed")
                    || !codeDescription.contains("schedule.pixiv.discovery-not-found"))
                throw new AssertionError("Missing extensible batch reason codes: " + batchCode);
        }
        options.required("imageFormats").required("description");
        options.required("acquisitionMode").required("description");
        var example = paths.required("/api/download/batches").required("post").required("requestBody").required("content")
                .required("application/json").required("examples").required("user-new").required("value");
        if (!example.required("definition").isObject()) throw new AssertionError("Batch example must accept structured JSON");
        var media = paths.required("/api/download/batches").required("post").required("requestBody")
                .required("content").required("application/json");
        String ref = media.required("schema").required("$ref").asString();
        var definition = spec.at(ref.substring(1)).at("/properties/definition");
        if (!"object".equals(definition.path("type").asString()))
            throw new AssertionError("Expected a JSON object at " + ref + "/properties/definition: " + definition);
        URI account = base.resolve("/api/pixiv/me/uid");
        var missing = client.send(admin.copy().uri(account).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (missing.statusCode() != 400 || missing.headers().firstValue("WWW-Authenticate").isPresent()
                || !"ACQUISITION_CREDENTIAL_REQUIRED".equals(JSON.readTree(missing.body()).path("code").asString()))
            throw new AssertionError("Missing Pixiv acquisition credential returned " + missing.statusCode());
        var invalid = client.send(HttpRequest.newBuilder(account).header("Authorization", "Bearer invalid-fixture").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (invalid.statusCode() != 401 || !"auth.program.invalid".equals(JSON.readTree(invalid.body()).path("code").asString()))
            throw new AssertionError("Invalid program key returned " + invalid.statusCode());
    }

    private static JsonNode checkQueryDefault(JsonNode operation, String name, Object value) {
        var parameter = operation.required("parameters").valueStream()
                .filter(p -> "query".equals(p.path("in").asString()) && name.equals(p.path("name").asString()))
                .findFirst().orElseThrow();
        var schema = parameter.required("schema");
        var expected = JSON.valueToTree(value);
        if (!expected.equals(schema.required("default")) || !expected.equals(parameter.required("example")))
            throw new AssertionError("Wrong default or example for " + name + " on " + operation.required("operationId"));
        return schema;
    }

    private static void verifyBatchItemPagination(HttpClient client, HttpRequest.Builder admin, URI items, JsonNode operation)
            throws Exception {
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        for (String name : List.of("offset", "limit")) {
            boolean offset = name.equals("offset");
            var schema = checkQueryDefault(operation, name, offset ? 0 : 100);
            if (!"integer".equals(schema.path("type").asString()) || !"int32".equals(schema.path("format").asString()))
                throw new AssertionError("Expected an int32 schema for " + name);
            var validator = registry.getSchema(schema);
            for (Object sample : List.of(-1, 0, 1, 2, 200, 201, "abc")) {
                boolean allowed = sample instanceof Integer n && (offset ? n >= 0 : n >= 1 && n <= 200);
                var uri = URI.create(items + "?" + name + "=" + sample);
                int status = client.send(admin.copy().uri(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                boolean valid = validator.validate(JSON.valueToTree(sample)).isEmpty();
                if (valid != allowed || status != (allowed ? 200 : 400))
                    throw new AssertionError(uri + " returned " + status + ", schema accepted=" + valid);
            }
        }
    }

    private static void verifyIntegrationExamples(JsonNode document) {
        var paths = document.required("paths");
        for (var route : Map.of("/api/download/status/active", "GET /api/download/tasks",
                "/api/download/status/{artworkId}", "GET /api/download/tasks/{taskId}").entrySet()) {
            var operation = paths.required(route.getKey()).required("get");
            if (!route.getValue().equals(operation.required("x-program-alternative").asString())
                    || operation.required("x-related-operations").valueStream().noneMatch(target -> route.getValue().equals(target.asString())))
                throw new AssertionError("Missing program polling route for " + route.getKey());
        }
        if (paths.required("/api/download/status").required("get").has("x-program-alternative"))
            throw new AssertionError("The download health probe does not identify a particular task");
        for (String path : List.of("/api/download/tasks", "/api/download/tasks/{taskId}", "/api/gallery/artworks", "/api/gallery/novels")) {
            var media = paths.required(path).requiredAt("/get/responses/200/content/application~1json");
            var samples = validatedExamples(document, path, media);
            if (path.startsWith("/api/gallery/") && samples.stream().noneMatch(value -> !value.path("content").isEmpty()))
                throw new AssertionError("Missing nonempty gallery example for " + path);
        }
        for (String path : List.of("/api/tools/media/previews", "/api/tools/media/tasks", "/api/tools/media/tasks/cancel",
                "/api/tools/media/capabilities", "/api/tools/file-import/previews", "/api/tools/file-import/tasks",
                "/api/tools/file-import/tasks/cancel", "/api/tools/file-import/completion/acknowledge",
                "/api/gallery/artworks/collect", "/api/gallery/artworks/delete", "/api/gallery/artworks/export",
                "/api/gallery/novels/collect", "/api/gallery/novels/delete", "/api/gallery/novels/export", "/api/gallery/novels/export/options")) {
            var media = paths.required(path).requiredAt("/post/requestBody/content/application~1json");
            validatedExamples(document, path, media);
        }
        for (String path : List.of("/api/plugins/install", "/api/collections/{id}/icon", "/api/narration/cast/voice/reference")) {
            var operation = paths.required(path).required("post");
            operation.requiredAt("/requestBody/content/multipart~1form-data/schema/properties/file");
            if (!operation.required("x-codeSamples").valueStream().anyMatch(sample -> sample.path("source").asString("").contains("file=@")))
                throw new AssertionError("Missing file upload example for " + path);
        }
    }

    private static List<JsonNode> validatedExamples(JsonNode document, String path, JsonNode media) {
        var contract = validator(document, media.required("schema"));
        var samples = new java.util.ArrayList<JsonNode>();
        if (media.has("example")) samples.add(media.get("example"));
        media.path("examples").properties().forEach(entry -> samples.add(entry.getValue().required("value")));
        if (samples.isEmpty()) throw new AssertionError("Missing integration example for " + path);
        for (var sample : samples) {
            var errors = contract.validate(sample);
            if (!errors.isEmpty()) throw new AssertionError(path + " example: " + errors);
        }
        return samples;
    }

    private static void verifyComparisonExamples(JsonNode document) {
        String description = document.requiredAt("/info/description").asString();
        var blocks = java.util.regex.Pattern.compile("(?ms)^```json\\R(.*?)\\R```$").matcher(description);
        for (String path : List.of("/api/download/tasks", "/api/download/batches")) {
            if (!blocks.find()) throw new AssertionError("Missing JSON request example for " + path);
            var example = JSON.readTree(blocks.group(1));
            var schema = document.required("paths").required(path).requiredAt("/post/requestBody/content/application~1json/schema");
            var errors = validator(document, schema).validate(example);
            if (!errors.isEmpty()) throw new AssertionError(path + " comparison example: " + errors);
            if (example.has("sourceType")) {
                String source = example.required("sourceType").asString();
                String definition = document.required("x-schedule-sources").required(source).required("inputSchemaJson").asString();
                var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
                var problems = registry.getSchema(JSON.readTree(definition)).validate(example.required("definition"));
                if (!problems.isEmpty()) throw new AssertionError(source + " comparison example: " + problems);
            }
        }
    }

    private static boolean declaresCode(JsonNode document, JsonNode schema, String code) {
        if (schema.has("$ref") && declaresCode(document, document.requiredAt(
                URI.create(schema.required("$ref").asString()).getFragment()), code)) return true;
        if (schema.at("/properties/code/enum").valueStream().anyMatch(item -> code.equals(item.asString()))) return true;
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            if (schema.path(keyword).valueStream().anyMatch(branch -> declaresCode(document, branch, code))) return true;
        }
        return false;
    }

    private static void verifyConditionalRequests(JsonNode spec) {
        for (String path : List.of("/api/tools/backfill/candidates", "/api/tools/backfill/tasks")) {
            checkRequest(spec, path, true, Map.of(), Map.of("artworkIds", List.of(42)));
            checkRequest(spec, path, false, Map.of("artworkIds", List.of()), Map.of("artworkIds", List.of(0)));
        }
        String prefix = "/api/tools/path-prefixes";
        checkRequest(spec, prefix, true, Map.of("updates", List.of(), "registerPaths", List.of("/example")));
        checkRequest(spec, prefix, false, Map.of(), Map.of("registerPaths", List.of(), "updates", List.of()),
                Map.of("registerPaths", List.of(123)));
        var changes = spec.required("paths").required(prefix).required("post").required("responses");
        var receipt = changes.required("200").requiredAt("/content/application~1json/schema");
        var fields = spec.requiredAt(receipt.required("$ref").asString().substring(1)).required("properties");
        for (String count : List.of("applied", "registered", "alreadyRegistered"))
            if (!"integer".equals(fields.required(count).required("type").asString()))
                throw new AssertionError("Missing path prefix receipt counter: " + count);
        if (!changes.required("500").required("description").asString().contains("PATH_PREFIX_WRITE_FAILED"))
            throw new AssertionError("Missing path prefix rollback response");
        checkRequest(spec, "/api/tools/directories", false, Map.of("artworkId", 1, "moved", false, "path", 123));
        String keys = "/api/security/program-keys";
        var policy = JSON.createObjectNode().put("name", "fixture");
        policy.putArray("permissions");
        policy.putArray("networks");
        checkRequest(spec, keys, false, policy);
        checkRequest(spec, keys, true, policy.put("anyIp", true));
        policy.withArray("networks").add("127.0.0.1");
        checkRequest(spec, keys, false, policy);
        checkRequest(spec, keys, true, policy.put("anyIp", false));
        String restore = "/api/settings/environment/restore";
        var request = Map.of("token", "fixture", "selected", List.of(), "mappedPaths", List.of(), "fileGroups", List.of(), "databasePaths", Map.of());
        checkRequest(spec, restore, false, request);
        var databases = (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(request);
        checkRequest(spec, restore, true, databases.put("includeDatabases", true));
        String layout = "/api/layout-feedback/state";
        var feedback = JSON.createObjectNode().put("surveyId", "fixture01").put("command", "record_seen");
        checkRequest(spec, layout, false, feedback);
        feedback.putArray("layoutIds").add("pixiv-batch-alt");
        checkRequest(spec, layout, true, feedback);
        checkRequest(spec, layout, false, feedback.put("command", "snooze"));
        checkRequest(spec, layout, true, feedback.putNull("layoutIds"));
    }

    private static void checkRequest(JsonNode spec, String path, boolean expectedValid, Object... bodies) {
        var media = spec.required("paths").required(path).requiredAt("/post/requestBody/content/application~1json/schema");
        var contract = validator(spec, media);
        for (var body : bodies) {
            var errors = contract.validate(JSON.valueToTree(body));
            if (errors.isEmpty() != expectedValid) throw new AssertionError(path + " " + body + " " + errors);
        }
    }

    private static void verifyHttpInputs(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec) throws Exception {
        var paths = spec.required("paths");
        record Input(String path, String requestPath, String in, String field, String reason) {}
        for (var sample : List.of(
                new Input("/api/pixiv/search", "/api/pixiv/search", "query", "word", "required"),
                new Input("/api/pixiv/search", "/api/pixiv/search?word=x&order=banana", "query", "order", "invalid_value"),
                new Input("/api/gallery/artworks", "/api/gallery/artworks?page=abc", "query", "page", "type"),
                new Input("/api/gallery/tags/lookup", "/api/gallery/tags/lookup", "query", "name", "required"),
                new Input("/api/library/artworks/{artworkId}", "/api/library/artworks/abc", "path", "artworkId", "type"),
                new Input("/api/tools/media/tasks/current", "/api/tools/media/tasks/current", "query", "pluginId", "required"),
                new Input("/api/tools/file-import/tasks/current", "/api/tools/file-import/tasks/current", "query", "pluginId", "required"),
                new Input("/api/download/tasks/{taskId}", "/api/download/tasks/abc", "path", "taskId", "type"))) {
            var response = client.send(admin.copy().uri(base.resolve(sample.requestPath())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            String code = sample.path().equals("/api/download/tasks/{taskId}") ? "DOWNLOAD_TASK_INVALID" : "error.request.param.invalid";
            expectError(response, 400, code, null);
            checkFieldError(response, spec, paths.required(sample.path()).required("get"), sample.in(), sample.field(), sample.reason());
        }
        for (String requestPath : List.of("/api/gallery/artworks?tagIds=1,abc", "/api/gallery/novels?tagIds=abc")) {
            var response = client.send(admin.copy().uri(base.resolve(requestPath)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            var body = JSON.readTree(response.body());
            if (response.statusCode() != 400 || !"tagIds".equals(body.path("field").asString()) || !"query".equals(body.path("in").asString()))
                throw new AssertionError("Invalid gallery filter: " + requestPath + " " + body);
        }
        for (String library : List.of("artworks", "novels")) {
            String route = "/api/gallery/" + library + "/delete";
            var operation = paths.required(route).required("post");
            var invalidSelection = Map.of("mode", "filter", "filter", Map.of("collectionIds", List.of(1, 0)));
            checkFieldError(post(client, admin, base, route, invalidSelection), spec, operation, "body", "/filter/collectionIds/1", "invalid_value");
            for (Object malformedId : List.of(987654321.9, "987654321")) {
                checkFieldError(post(client, admin, base, route, Map.of("mode", "ids", "ids", List.of(malformedId))),
                        spec, operation, "body", "/ids/0", "type");
                checkFieldError(post(client, admin, base, route, Map.of("mode", "filter", "filter", Map.of("tagIds", List.of(malformedId)))),
                        spec, operation, "body", "/filter/tagIds/0", "type");
            }
            checkFieldError(post(client, admin, base, route, Map.of("mode", "filter", "ids", List.of(987654321))),
                    spec, operation, "body", "/filter", "required");
            var selection = Map.of("mode", "ids", "ids", List.of(987654321));
            String collect = "/api/gallery/" + library + "/collect";
            var collectOperation = paths.required(collect).required("post");
            checkFieldError(post(client, admin, base, collect, selection), spec, collectOperation, "body", "/collectionId", "required");
            if (validator(spec, collectOperation.requiredAt("/requestBody/content/application~1json/schema"))
                    .validate(JSON.valueToTree(selection)).isEmpty())
                throw new AssertionError("Missing destination collection must be rejected by " + collect + " schema");
            String export = "/api/gallery/" + library + "/export";
            checkFieldError(post(client, admin, base, export, Map.of("mode", "filtter", "ids", List.of(987654321))),
                    spec, paths.required(export).required("post"), "body", "/mode", "invalid_value");
        }
        var missing = client.send(admin.copy().uri(base.resolve("/api/library/artworks/987654321"))
                .header("Accept", "application/xml").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (missing.statusCode() != 400 || missing.body().length != 0 || missing.headers().firstValue("Content-Type").isPresent())
            throw new AssertionError("A missing library record must retain its empty 400 response");
        var partial = client.send(admin.copy().uri(base.resolve("/api/library/artworks/987654321/partial")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        expectError(partial, 404, "error.request.not-found", null);
        var task = client.send(admin.copy().uri(base.resolve("/api/download/tasks/" + UUID.randomUUID())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        expectError(task, 404, "DOWNLOAD_TASK_NOT_FOUND", null);
        var progress = get(client, admin.copy().uri(base.resolve("/api/download/status/987654321")));
        if (progress.required("success").asBoolean()) throw new AssertionError("Missing session download progress");
        var tag = client.send(admin.copy().uri(base.resolve("/api/gallery/tags/lookup?name=absent-fixture-tag")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (tag.statusCode() != 404) throw new AssertionError("Missing tag returned " + tag.statusCode());
        var invalidBody = post(client, admin, base, "/api/collections", Map.of("nmae", "x"));
        expectError(invalidBody, 400, "error.request.validation", null);
        checkFieldError(invalidBody, spec, paths.required("/api/collections").required("post"), "body", "/name", "required");
        for (String path : List.of("/api/plugins/install", "/api/collections/{id}/icon", "/api/narration/cast/voice/reference")) {
            String concrete = path.replace("{id}", "1");
            if (path.endsWith("/voice/reference")) concrete += "?castId=1&characterId=1";
            var response = client.send(admin.copy().uri(base.resolve(concrete)).header("Origin", base.toString())
                    .header("Content-Type", "multipart/form-data; boundary=Sample")
                    .POST(HttpRequest.BodyPublishers.ofString("--Sample\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\nx\r\n--Sample--\r\n")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (path.equals("/api/plugins/install")) {
                expectError(response, 400, "REJECTED_EMPTY", null);
            } else {
                expectError(response, 400, "error.request.param.invalid", null);
                checkFieldError(response, spec, paths.required(path).required("post"), "part", "file", "required");
            }
        }
        for (String path : List.of("/api/library/artworks/{artworkId}", "/api/gallery/artworks", "/api/auth/check", "/api/download/tasks")) {
            var operation = paths.required(path).required("get");
            var response = client.send(admin.copy().uri(base.resolve(path.replace("{artworkId}", "42")))
                    .header("Accept", "application/xml").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 406 || response.body().length != 0 || !operation.requiredAt("/responses/406").path("content").isEmpty())
                throw new AssertionError("Expected a documented bodyless 406: " + path);
        }
        for (String path : List.of("/api/settings/validate", "/api/download/tasks")) {
            var operation = paths.required(path).required("post");
            var response = client.send(admin.copy().uri(base.resolve(path)).header("Origin", base.toString())
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (path.equals("/api/settings/validate")) {
                expectError(response, 415, "http.status.415", null);
                operation.requiredAt("/responses/415/content/application~1json/schema");
            } else {
                expectError(response, 400, "DOWNLOAD_TASK_INVALID", null);
                checkFieldError(response, spec, operation, "body", "/requestId", "required");
                if (operation.required("responses").has("415")) throw new AssertionError("The raw download command reads the body itself");
            }
        }
        for (String path : List.of("/api/plugins/install", "/api/collections/{id}/icon", "/api/narration/cast/voice/reference")) {
            var operation = paths.required(path).required("post");
            var response = post(client, admin, base, path.replace("{id}", "1"), Map.of());
            expectError(response, 415, "http.status.415", null);
            operation.requiredAt("/responses/415/content/application~1json/schema");
        }
    }

    private static void checkFieldError(HttpResponse<byte[]> response, JsonNode spec, JsonNode operation,
                                        String in, String field, String reason) {
        var body = JSON.readTree(response.body());
        var schema = operation.requiredAt("/responses/400/content/application~1json/schema");
        if (response.statusCode() != 400 || !in.equals(body.path("in").asString(""))
                || !field.equals(body.path("field").asString("")) || !reason.equals(body.path("reason").asString(""))
                || body.path("error").asString("").contains("Failed to convert") || !validator(spec, schema).validate(body).isEmpty())
            throw new AssertionError(operation.required("operationId") + " returned " + response.statusCode() + " " + body);
    }

    private static void verifyContractBoundaries(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec) throws Exception {
        var paths = spec.required("paths");
        var notification = "/api/notifications/{id}/content";
        paths.required(notification).requiredAt("/get/responses/406");
        var html = client.send(admin.copy().uri(base.resolve("/api/notifications/missing/content"))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (html.statusCode() != 406 || html.body().length != 0) throw new AssertionError("HTML-only content negotiation: " + html.statusCode());
        for (String query : List.of("", "?novelId=0", "?seriesId=-1")) {
            var response = client.send(admin.copy().uri(base.resolve("/api/translation/jobs" + query)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            expectError(response, 400, "TRANSLATION_INVALID_REQUEST", null);
        }
        for (String query : List.of("?novelId=42", "?seriesId=7", "?novelId=42&seriesId=7", "?novelId=42&seriesId=0"))
            if (!get(client, admin.copy().uri(base.resolve("/api/translation/jobs" + query))).isArray())
                throw new AssertionError("Missing translation job list for " + query);
        for (String route : List.of("/api/settings", "/api/settings/validate")) {
            var schema = validator(spec, paths.required(route).requiredAt("/post/requestBody/content/application~1json/schema"));
            for (var body : List.of(Map.of(), Map.of("changes", List.of()))) {
                if (schema.validate(JSON.valueToTree(body)).isEmpty()) throw new AssertionError("Settings schema accepted " + body);
                var bad = post(client, admin, base, route, body);
                expectError(bad, 400, "INVALID_TOOL_REQUEST", null);
                if (!"/changes".equals(JSON.readTree(bad.body()).required("field").asString())) throw new AssertionError(route);
            }
        }
        var catalog = get(client, admin.copy().uri(base.resolve("/api/settings")));
        var secret = catalog.valueStream().filter(f -> f.path("sensitive").asBoolean() && f.path("owner").isString()).findFirst().orElseThrow();
        var setting = JSON.createObjectNode();
        for (String field : List.of("owner", "packageId", "generation", "key")) setting.set(field, secret.required(field));
        setting.put("action", "KEEP");
        for (String path : List.of("/api/settings", "/api/settings/validate")) {
            var kept = post(client, admin, base, path, Map.of("changes", List.of(setting)));
            if (kept.statusCode() != 200 || !JSON.createArrayNode().equals(JSON.readTree(kept.body()))) throw new AssertionError("KEEP " + path + ": " + kept.statusCode());
            var bad = post(client, admin, base, path, Map.of("changes", List.of(setting.deepCopy().put("value", 80))));
            expectError(bad, 400, "INVALID_TOOL_REQUEST", null);
            if (!"/changes/0/value".equals(JSON.readTree(bad.body()).path("field").asString())) throw new AssertionError("Missing setting pointer");
            var stale = post(client, admin, base, path, Map.of("changes", List.of(setting.deepCopy().putNull("packageId"))));
            expectError(stale, 409, "SETTINGS_OWNER_CHANGED", null);
            if (!"/changes/0/packageId".equals(JSON.readTree(stale.body()).path("field").asString())) throw new AssertionError("Missing packageId pointer");
        }
        var expired = get(client, admin.copy().uri(base.resolve("/api/archive/status/unknown-fixture")));
        if (!"expired".equals(expired.path("status").asString()) || expired.size() != 1) throw new AssertionError(expired);
        var source = paths.required("/api/download/batches").requiredAt("/post/requestBody/content/application~1json");
        var create = spec.at(source.requiredAt("/schema/$ref").asString().substring(1));
        var choices = create.requiredAt("/discriminator/mapping");
        if (!"sourceType".equals(create.requiredAt("/discriminator/propertyName").asString())) throw new AssertionError(create);
        for (var example : source.required("examples").properties()) {
            var body = example.getValue().required("value");
            var branch = spec.at(choices.required(example.getKey()).asString().substring(1));
            var definition = spec.at(branch.requiredAt("/properties/definition/$ref").asString().substring(1));
            var inner = JSON.readTree(spec.required("x-schedule-sources").required(example.getKey()).required("inputSchemaJson").asString());
            if (!validator(spec, branch).validate(body).isEmpty() || !validator(spec, definition).validate(body.required("definition")).isEmpty()
                    || !com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                            .getSchema(inner).validate(body.required("definition")).isEmpty())
                throw new AssertionError("Batch and standalone schema disagree for " + example.getKey());
        }
        var valid = source.requiredAt("/examples/explicit-list/value");
        var validator = validator(spec, source.required("schema"));
        if (!validator.validate(valid).isEmpty()) throw new AssertionError("Invalid batch example: " + validator.validate(valid));
        var invalid = (tools.jackson.databind.node.ObjectNode) valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) invalid.required("definition")).put("kind", "illust");
        if (validator.validate(invalid).isEmpty()) throw new AssertionError("Batch input ignored the source-specific kind");
        for (String path : List.of("/api/sse/download", "/api/sse/download/{artworkId}")) {
            var events = paths.required(path).requiredAt("/get/responses/200/content/text~1event-stream/x-sse-events");
            for (String name : List.of("heartbeat", "sse-closing", "plugin-unavailable"))
                if (!"string".equals(events.required(name).required("type").asString())) throw new AssertionError(name);
            if (events.has("aggregated-ready") != path.endsWith("download")) throw new AssertionError(path);
        }
        for (String path : List.of("/api/gallery/artworks", "/api/gallery/novels")) {
            var param = paths.required(path).requiredAt("/get/parameters").valueStream()
                    .filter(p -> "collectionIds".equals(p.path("name").asString())).findFirst().orElseThrow();
            if (!"array".equals(param.at("/schema/type").asString()) || param.required("explode").asBoolean()) throw new AssertionError(param);
        }
    }

    private static void verifyClientContracts(JsonNode document) {
        var paths = document.required("paths");
        for (String library : List.of("artworks", "novels")) {
            var export = paths.required("/api/gallery/" + library + "/export").required("post");
            var body = export.requiredAt("/requestBody/content/application~1json/schema");
            var schema = validator(document, body);
            for (String name : List.of("confirmAll", "deleteAfter")) {
                var flags = JSON.createObjectNode().put("mode", "ids");
                flags.putArray("ids");
                if (!schema.validate(flags.put(name, true)).isEmpty() || !schema.validate(flags.putNull(name)).isEmpty()
                        || schema.validate(flags.put(name, 1)).isEmpty() || schema.validate(flags.put(name, "true")).isEmpty())
                    throw new AssertionError(library + " export must accept only boolean or null for " + name);
            }
            var grant = export.requiredAt("/x-program-permissions/submit/0");
            if (!"request#/deleteAfter".equals(grant.requiredAt("/when/keyFrom").asString())
                    || !grant.requiredAt("/when/value").asBoolean()
                    || !"/programPermission".equals(grant.requiredAt("/reportedIn/pointer").asString()))
                throw new AssertionError("Missing delete permission condition on " + library + " export");
        }
        for (String legacy : List.of("/api/cancel/{artworkId}", "/api/download/cancel/{artworkId}")) {
            var retired = paths.required(legacy).required("post");
            if (!retired.required("parameters").valueStream().anyMatch(p -> "path".equals(p.path("in").asString())
                    && "artworkId".equals(p.path("name").asString()) && p.path("required").asBoolean()))
                throw new AssertionError("Missing artworkId on retired route " + legacy);
        }
        if (!declaresCode(document, paths.required("/api/security/program-keys/self").requiredAt(
                "/get/responses/401/content/application~1json/schema"), "auth.program.required"))
            throw new AssertionError("Missing program key requirement on self lookup");
        // Codes named in a response description must be discoverable without parsing the prose.
        var namedCode = java.util.regex.Pattern.compile("\\b(?:(?:schedule\\.error|batch|task\\.queue|auth\\.program|work\\.selection|error)\\.[a-z][a-z.-]*[a-z]"
                + "|DATABASE_UNAVAILABLE|ACQUISITION_CREDENTIAL_REQUIRED|HOOK_[A-Z_]+)\\b");
        var confirmationRoutes = java.util.Set.of("/api/gallery/artworks/delete", "/api/gallery/artworks/export",
                "/api/gallery/novels/delete", "/api/gallery/novels/export");
        for (var path : paths.properties()) {
            for (var entry : path.getValue().properties()) {
                var operation = entry.getValue();
                for (var status : operation.path("responses").properties()) {
                    if (!(status.getKey().startsWith("4") || status.getKey().startsWith("5"))) continue;
                    var response = status.getValue();
                    var mentions = namedCode.matcher(response.path("description").asString(""));
                    while (mentions.find()) {
                        if (!declaresCode(document, response.at("/content/application~1json/schema"), mentions.group()))
                            throw new AssertionError(path.getKey() + " " + status.getKey() + " omits " + mentions.group());
                    }
                }
                boolean destructive = "post".equals(entry.getKey()) && confirmationRoutes.contains(path.getKey());
                if (declaresCode(document, operation.at("/responses/400/content/application~1json/schema"),
                        "work.selection.confirm-all") != destructive)
                    throw new AssertionError("Incorrect full-library confirmation schema on " + entry.getKey() + " " + path.getKey());
                if (operation.has("x-csrf-origin") && !declaresCode(document,
                        operation.requiredAt("/responses/403/content/application~1json/schema"), "auth.csrf.invalid"))
                    throw new AssertionError("Missing Origin error schema on " + path.getKey());
            }
            if (!path.getKey().startsWith("/api/pixiv/user/{userId}/")) continue;
            for (var operation : path.getValue().properties()) {
                var id = operation.getValue().required("parameters").valueStream()
                        .filter(p -> "userId".equals(p.path("name").asString()) && "path".equals(p.path("in").asString()))
                        .findFirst().orElseThrow();
                if (!"string".equals(id.at("/schema/type").asString()) || id.required("schema").has("minimum"))
                    throw new AssertionError("Expected a string author ID for " + path.getKey());
            }
        }
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        var schedule = paths.required("/api/schedule/tasks").required("post");
        var body = schedule.at("/requestBody/content/application~1json");
        var createSchema = JSON.createObjectNode();
        createSchema.set("components", document.required("components"));
        createSchema.set("allOf", JSON.createArrayNode().add(body.required("schema")));
        var condition = document.at("/components/schemas/top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition");
        if (!registry.getSchema(condition).validate(JSON.readTree("""
                {"key":"flag","operator":"TRUE","value":null}
                """)).isEmpty()) throw new AssertionError("Settings condition rejected an unused null comparison value");
        for (String path : List.of("/api/gallery/artworks", "/api/gallery/novels", "/api/gallery/novels/authors",
                "/api/gallery/novels/series", "/api/authors/paged", "/api/series/paged", "/api/downloaded/history/paged",
                "/api/duplicates/groups", "/api/duplicates/rescan", "/api/pixiv/search", "/api/pixiv/series/{seriesId}",
                "/api/pixiv/novel-search", "/api/pixiv/novel/series/{seriesId}", "/api/pixiv/me/follow-latest")) {
            var operation = paths.required(path).required(path.endsWith("/rescan") ? "post" : "get");
            var page = operation.required("parameters").valueStream()
                    .filter(p -> List.of("page", "p").contains(p.path("name").asString())).findFirst().orElseThrow();
            if (!registry.getSchema(page.required("schema")).validate(JSON.readTree("-1")).isEmpty())
                throw new AssertionError("Query schema rejected a page that " + path + " normalizes");
        }
        checkQueryDefault(paths.required("/api/pixiv/user/{userId}/downloads").required("post"), "kind", "illust");
        var galleryDefaults = Map.of("sort", "date", "order", "desc", "searchType", "all", "r18", "any", "ai", "any");
        for (String path : List.of("/api/gallery/artworks", "/api/gallery/novels")) {
            var operation = paths.required(path).required("get");
            for (var entry : galleryDefaults.entrySet()) {
                var schema = checkQueryDefault(operation, entry.getKey(), entry.getValue());
                if (schema.required("examples").valueStream().noneMatch(schema.required("default")::equals))
                    throw new AssertionError("Missing gallery example for " + entry.getKey() + " on " + path);
            }
            for (var param : operation.required("parameters")) {
                String name = param.required("name").asString();
                if (!List.of("sort", "order", "searchType", "r18", "ai", "sourceTimeField", "fileManagement").contains(name)) continue;
                boolean accepted = registry.getSchema(param.required("schema")).validate(JSON.readTree("\"unknown\"")).isEmpty();
                if (accepted == List.of("sourceTimeField", "fileManagement").contains(name))
                    throw new AssertionError("Unexpected schema for unknown " + name + " on " + path);
            }
        }
        for (var entry : Map.of("/api/download/tasks", "/api/download/tasks/",
                "/api/download/batches", "/api/download/batches/", "/api/pixiv/user/{userId}/downloads", "/api/download/batches/",
                "/api/tools/backfill/tasks", "/api/tools/backfill/tasks/", "/api/translation/jobs", "/api/translation/jobs/").entrySet()) {
            var location = paths.required(entry.getKey()).requiredAt("/post/responses/202/headers/Location");
            String example = location.required("schema").required("example").asString();
            if (!location.required("required").asBoolean() || location.required("description").asString().isBlank()
                    || !example.startsWith(entry.getValue())) throw new AssertionError("Invalid Location for " + entry.getKey());
            UUID.fromString(example.substring(entry.getValue().length()));
        }
        var queue = paths.required("/api/download/queue/{queueType}/cancel").required("post");
        var queueSchema = JSON.createObjectNode();
        queueSchema.set("components", document.required("components"));
        queueSchema.set("allOf", JSON.createArrayNode().add(queue.at("/requestBody/content/application~1json/schema")));
        var queueValidator = registry.getSchema(queueSchema);
        var cancellation = JSON.createObjectNode().put("workKey", "42");
        cancellation.putObject("owner").put("pluginId", "provider").put("packageId", "pkg").put("generation", 1).put("publicationId", 1);
        if (!queueValidator.validate(cancellation).isEmpty()) throw new AssertionError("Complete queue cancellation rejected");
        for (String field : List.of("workKey", "owner")) {
            var incomplete = cancellation.deepCopy();
            incomplete.remove(field);
            if (queueValidator.validate(incomplete).isEmpty()) throw new AssertionError("Queue cancellation accepted without " + field);
        }
        var validator = registry.getSchema(createSchema);
        var updateSchema = createSchema.deepCopy();
        updateSchema.set("allOf", JSON.createArrayNode().add(paths.required("/api/schedule/tasks/{id}").required("put")
                .at("/requestBody/content/application~1json/schema")));
        var updateValidator = registry.getSchema(updateSchema);
        for (String kind : List.of("interval", "cron")) {
            var example = (tools.jackson.databind.node.ObjectNode) body.required("examples").required("explicit-list").required("value").deepCopy();
            example.put("triggerKind", kind);
            example.remove("intervalMinutes");
            example.remove("cronExpr");
            if (validator.validate(example).isEmpty()) throw new AssertionError("Missing trigger value accepted: " + kind);
            if (kind.equals("cron")) example.put("cronExpr", "0 0 9 * * *");
            else example.put("intervalMinutes", 1);
            if (!validator.validate(example).isEmpty() || updateValidator.validate(example).isEmpty())
                throw new AssertionError("Only creation accepts a missing task version: " + kind);
            example.putNull("expectedStateVersion");
            if (!validator.validate(example).isEmpty() || updateValidator.validate(example).isEmpty())
                throw new AssertionError("Only creation accepts a null task version: " + kind);
            example.put("expectedStateVersion", 0);
            if (validator.validate(example).isEmpty() || !updateValidator.validate(example).isEmpty())
                throw new AssertionError("Only editing accepts an observed task version: " + kind);
            example.put("expectedStateVersion", -1);
            if (updateValidator.validate(example).isEmpty()) throw new AssertionError("Negative task version accepted");
        }
        if (!"origin-or-referer".equals(schedule.required("x-csrf-origin").asString()))
            throw new AssertionError("Missing schedule origin requirement");
    }

    private static void verifyDownloadEventContract(JsonNode document) {
        var paths = document.required("paths");
        for (String path : List.of("/api/sse/download", "/api/sse/download/{artworkId}")) {
            var media = paths.required(path).required("get").at("/responses/200/content/text~1event-stream");
            var ref = media.at("/x-sse-events/download-status/$ref").asString();
            if (!"string".equals(media.at("/schema/type").asString()))
                throw new AssertionError("Expected an SSE text stream for " + path);
            var data = document.at(ref.substring(1));
            var fields = data.required("properties");
            for (var field : fields.properties()) {
                if (field.getValue().path("description").asString("").isBlank())
                    throw new AssertionError("Missing download-status field description: " + field.getKey());
            }
            var progress = fields.required("progress");
            if (!"integer".equals(progress.required("type").asString()) || progress.required("minimum").asInt() != 0
                    || progress.required("maximum").asInt() != 100 || data.required("required").valueStream().anyMatch(v -> "progress".equals(v.asString())))
                throw new AssertionError("Expected optional download progress from 0 to 100");
            if (!"string".equals(fields.required("status").required("type").asString()) || fields.required("status").has("enum"))
                throw new AssertionError("Expected a localized download-status label");
        }
    }

    private static void verifyPermissionStages(JsonNode document) {
        var paths = document.required("paths");
        var sources = document.required("x-schedule-sources");
        var grammar = document.required("x-program-permission-conditions");
        if (!document.at("/info/description").asString().contains("x-program-permission-conditions"))
            throw new AssertionError("No introduction to the permission grammar");
        var batch = paths.required("/api/download/batches").required("post");
        if (!batch.required("description").asString().contains("programPermission")
                || !batch.at("/responses/202/description").asString().contains("programPermission"))
            throw new AssertionError("The batch operation must explain where to find a missing grant after acceptance");
        var errors = document.required("components").required("schemas");
        var credential = errors.required("top.sywyar.pixivdownload.setup.AuthFilter.ProgramCredentialError");
        if (!credential.required("description").asString().contains("ProgramKeyUnsupported")
                || credential.at("/properties/code/enum").valueStream().anyMatch(v -> v.asString().equals("auth.program.unsupported")))
            throw new AssertionError("Unsupported routes use their own error schema");
        var create = paths.required("/api/schedule/tasks").required("post").required("x-program-permissions");
        if (!"core:schedule.sources.read".equals(create.at("/discovery/permission").asString())
                || !"schedulePermission".equals(create.at("/submit/0/field").asString()))
            throw new AssertionError("Missing source management or discovery grant");
        var edit = paths.required("/api/schedule/tasks/{id}").required("put").required("x-program-permissions");
        if (!edit.required("submit").valueStream().anyMatch(grant -> "task#/sourceOwnerPluginId".equals(grant.path("ownerFrom").asString(""))))
            throw new AssertionError("Missing existing task owner check");
        for (var source : sources.properties()) {
            var entry = source.getValue();
            String owner = entry.required("ownerPluginId").asString();
            if (!(owner + ":schedule.manage").equals(entry.required("schedulePermission").asString())
                    || !(owner + ":batches.execute").equals(entry.required("batchSubmitPermission").asString()))
                throw new AssertionError("Wrong permissions for " + source.getKey());
        }
        for (var path : paths.properties()) for (var verb : path.getValue().properties()) {
            var operation = verb.getValue();
            if (!operation.has("x-program-permissions")) continue;
            var stages = operation.required("x-program-permissions");
            for (var phase : stages.properties()) {
                checkPermissionTerm(grammar.required("phases"), phase.getKey());
                checkConditionTerms(grammar, phase.getValue());
            }
            if (!stages.required("route").equals(operation.required("x-program-permission")))
                throw new AssertionError("Route grant mismatch for " + path.getKey());
            for (var grant : stages.required("submit")) {
                if (grant.has("catalog")) {
                    var catalog = document.at(grant.required("catalog").asString().substring(1));
                    String field = grant.required("field").asString();
                    if (!catalog.isObject() || catalog.valueStream().anyMatch(entry -> !entry.has(field)))
                        throw new AssertionError("Unresolvable grant on " + path.getKey() + ": " + field);
                }
            }
        }
    }

    private static void checkConditionTerms(JsonNode grammar, JsonNode condition) {
        if (condition.isArray()) {
            for (var entry : condition) checkConditionTerms(grammar, entry);
            return;
        }
        if (!condition.isObject()) return;
        for (var field : condition.properties()) {
            checkPermissionTerm(grammar.required("fields"), field.getKey());
            if (field.getKey().equals("ownerFrom") || field.getKey().equals("keyFrom")) {
                String expression = field.getValue().asString();
                int split = expression.indexOf('#');
                if (split < 1) throw new AssertionError("Missing context in " + expression);
                checkPermissionTerm(grammar.at("/expression/contexts"), expression.substring(0, split));
                tools.jackson.core.JsonPointer.compile(expression.substring(split + 1));
            }
            checkConditionTerms(grammar, field.getValue());
        }
    }

    private static void checkPermissionTerm(JsonNode definitions, String term) {
        if (definitions.path(term).asString("").isBlank()) throw new AssertionError("Undocumented permission term: " + term);
    }

    private static void verifyProgramDenials(HttpClient client, HttpRequest.Builder admin, URI base, JsonNode spec) throws Exception {
        var policy = Map.of("name", "permission-contract", "anyIp", false, "networks", List.of("127.0.0.1"),
                "permissions", List.of("core:settings.write", "core:schedule.create", "core:batches.create", "core:batches.read",
                        "download-workbench:schedule.manage", "download-workbench:batches.execute",
                        "core:plugins.stop", "notification:plugins.manage", "plugin-market:read", "gallery-tools:duplicates.read",
                        "gallery:export", "novel:library.export"));
        var issued = post(client, admin, base, "/api/security/program-keys", policy);
        if (issued.statusCode() != 201) throw new AssertionError("Could not issue fixture key: " + issued.statusCode());
        var key = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + JSON.readTree(issued.body()).required("secret").asString());
        for (var route : retiredClassifierRoutes().entrySet()) {
            var uri = base.resolve(route.getKey().replace("{artworkId}", "bad-id"));
            for (String verb : route.getValue().split(" ")) {
                var body = verb.equals("get") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{");
                var response = client.send(admin.copy().uri(uri).setHeader("Accept", "image/png")
                        .method(verb.toUpperCase(java.util.Locale.ROOT), body).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 410 || response.body().length != 0)
                    throw new AssertionError("Expected empty 410 for " + verb + " " + uri.getPath());
                if (verb.equals("get") && client.send(admin.copy().uri(uri).method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.discarding()).statusCode() != 410)
                    throw new AssertionError("Expected HEAD 410 for " + uri.getPath());
            }
        }
        for (var denied : Map.of("/api/library/artworks/42/move", "core:library.move",
                "/api/tools/classifier/moves", "core:classifier.write").entrySet())
            expectError(post(client, key, base, denied.getKey(), Map.of()), 403, "auth.program.forbidden", denied.getValue());
        expectError(client.send(key.copy().uri(base.resolve("/api/tools/classifier/settings")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()), 403, "auth.program.forbidden", "core:classifier.read");
        expectError(post(client, key, base, "/api/tools/ffmpeg/tasks/" + UUID.randomUUID() + "/cancel", Map.of()),
                403, "auth.program.forbidden", "core:ffmpeg.cancel");
        var self = get(client, key.copy().uri(base.resolve("/api/security/program-keys/self")));
        OpenApiGetProbe.validate(spec, "/api/security/program-keys/self", "program key", self);
        for (var route : Map.of("artworks", "gallery:delete", "novels", "novel:library.delete").entrySet()) {
            // An empty explicit selection also keeps this check free of deletion side effects.
            expectError(post(client, key, base, "/api/gallery/" + route.getKey() + "/export",
                    Map.of("mode", "ids", "ids", List.of(), "deleteAfter", true)), 403, "auth.program.forbidden", route.getValue());
        }
        var catalog = get(client, key.copy().uri(base.resolve("/api/plugin-market/catalog?repositoryId=configured")));
        OpenApiGetProbe.validate(spec, "/api/plugin-market/catalog", "program key", catalog);
        for (String pointer : List.of("/nextCursor", "/entries/0/market/links", "/entries/0/installation/version",
                "/entries/0/packages/0/verification/revocation/fetchedAt", "/entries/0/packages/0/verification/revocation/generatedTime",
                "/entries/0/packages/0/verification/revocation/nextUpdate", "/entries/0/packages/0/verification/revocation/graceUntil"))
            if (!catalog.requiredAt(pointer).isNull()) throw new AssertionError("Expected explicit null at " + pointer);
        var meta = spec.at("/components/schemas").required("top.sywyar.pixivdownload.plugin.market.PluginMarketMetaView");
        var links = validator(spec, meta.at("/properties/links"));
        if (!links.validate(JSON.createArrayNode()).isEmpty() || links.validate(JSON.createArrayNode().addNull()).isEmpty())
            throw new AssertionError("Nullable link list must still require non-null link objects");
        String scan = "/api/duplicates/scan/status";
        OpenApiGetProbe.validate(spec, scan, "program key", get(client, key.copy().uri(base.resolve(scan))));
        expectError(post(client, key, base, "/api/download/pixiv", Map.of()), 403, "auth.program.unsupported", null);
        for (String path : List.of("/api/auth/check", "/api/download/status", "/api/download/status/active",
                "/api/download/status/42", "/api/security/program-keys")) {
            var rejected = client.send(key.copy().uri(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            expectError(rejected, 403, "auth.program.unsupported", null);
        }
        var impact = get(client, admin.copy().uri(base.resolve("/api/plugins/notification/impact")));
        var consumer = impact.required("consumers").get(0);
        if (consumer == null) throw new AssertionError("Expected an installed notification consumer");
        // An obsolete fingerprint also prevents any lifecycle action if the permission check regresses.
        expectError(post(client, key, base, "/api/plugins/notification/actions/stop", Map.of("fingerprint", "0".repeat(64))),
                403, "auth.program.forbidden", consumer.required("id").asString() + ":plugins.manage");
        var fields = get(client, admin.copy().uri(base.resolve("/api/settings")));
        var plugin = fields.valueStream().filter(field -> field.path("owner").isString()).findFirst().orElseThrow();
        String owner = plugin.required("owner").asString();
        for (String target : List.of(owner, "unknown-fixture-owner")) {
            var change = Map.of("changes", List.of(Map.of("owner", target, "packageId", plugin.required("packageId").asString(),
                    "generation", plugin.required("generation").asLong(), "key", plugin.required("key").asString(), "action", "SET", "value", "test")));
            expectError(post(client, key, base, "/api/settings/validate", change), 403, "auth.program.forbidden",
                    target.equals(owner) ? owner + ":settings.write" : null);
        }
        var global = Map.of("changes", List.of(Map.of("key", "app.language", "value", "en-US", "generation", 0)));
        if (post(client, key, base, "/api/settings/validate", global).statusCode() != 200)
            throw new AssertionError("Core settings validation rejected its route grant");

        var body = Map.of("name", "contract", "sourceType", "user-new", "activationToken", "retired",
                "triggerKind", "interval", "intervalMinutes", 60, "definitionJson", "{}");
        expectError(post(client, key, base, "/api/schedule/tasks", body), 409, "schedule.error.source-publication-changed", null);
        var unknown = new java.util.HashMap<String, Object>(body);
        unknown.put("sourceType", "unknown-fixture-source");
        expectError(post(client, key, base, "/api/schedule/tasks", unknown), 403, "auth.program.forbidden", null);

        var author = spec.required("paths").required("/api/pixiv/user/{userId}/downloads").required("post");
        var stages = author.required("x-program-permissions");
        if (stages.has("discovery") || !stages.required("route").equals(author.required("x-program-permission"))
                || !stages.required("submit").valueStream().anyMatch(grant -> "core:batches.create".equals(grant.path("permission").asString(""))))
            throw new AssertionError("Author batch submission grants do not describe the fixed source");
        var execution = stages.required("execution");
        var source = spec.at(execution.required("catalog").asString().substring(1)).required(execution.required("value").asString());
        String missing = source.required(execution.required("field").asString()).required("novel").asString();
        var reported = execution.required("reportedIn");
        if (!"core:batches.read".equals(reported.required("permission").asString())) throw new AssertionError("Missing polling grant");
        for (String id : List.of("0", "-1", "abc", "9223372036854775808")) {
            expectError(post(client, key, base, "/api/pixiv/user/" + id + "/downloads", Map.of()),
                    400, "error.request.param.invalid", null);
        }
        var accepted = post(client, key, base, "/api/pixiv/user/0009223372036854775807/downloads?kind=novel", Map.of());
        if (accepted.statusCode() != 202) throw new AssertionError("Author batch returned " + accepted.statusCode());
        var location = base.resolve(accepted.headers().firstValue("Location").orElseThrow());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            var result = get(client, key.copy().uri(location));
            if ("FAILED".equals(result.path("state").asString())) {
                if (!"auth.program.forbidden".equals(result.at(reported.required("codePointer").asString()).asString())
                        || !missing.equals(result.at(reported.required("pointer").asString()).asString()))
                    throw new AssertionError("Batch failure did not identify the execution grant: " + result.path("code"));
                var cleared = client.send(admin.copy().uri(location).header("Origin", base.toString()).DELETE().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (cleared.statusCode() != 204) throw new AssertionError("Could not clear terminal fixture batch: " + cleared.statusCode());
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Batch failed to report its missing executor grant");
    }

    static HttpResponse<byte[]> post(HttpClient client, HttpRequest.Builder identity, URI base,
                                             String path, Object body) throws Exception {
        return client.send(identity.copy().uri(base.resolve(path)).header("Origin", base.toString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void expectError(HttpResponse<byte[]> response, int status, String code, String permission) {
        var body = JSON.readTree(response.body());
        if (response.statusCode() != status || !code.equals(body.path("code").asString(""))
                || (permission == null ? body.has("programPermission") : !permission.equals(body.path("programPermission").asString(""))))
            throw new AssertionError("Expected HTTP " + status + " " + code + " for " + permission
                    + ", got " + response.statusCode() + " " + body.path("code") + " " + body.path("programPermission"));
    }

    private static void verifyBatchAndSearchDocs(JsonNode document) {
        var paths = document.required("paths");
        for (String route : List.of("/api/download/batches/{id}/resolve", "/api/schedule/tasks/{id}/pending/resolve")) {
            String invalidChoice = paths.required(route).at("/post/responses/400/description").asString();
            if (!List.of("/userAction", "TRUNCATE", "DEFAULT_NAME", "CANCEL").stream().allMatch(invalidChoice::contains))
                throw new AssertionError("Missing supported decisions in " + route);
        }
        var batch = paths.required("/api/download/batches").required("post");
        String details = batch.required("description").asString();
        int rejection = details.indexOf("`400 INVALID_TOOL_REQUEST`");
        if (rejection < 0 || details.indexOf("`202`", rejection) < 0 || !details.contains("`/definition`"))
            throw new AssertionError("Missing definition rejection before the batch receipt");
        var rejected = batch.at("/responses/400");
        if (!rejected.required("description").asString().contains("INVALID_TOOL_REQUEST")
                || rejected.path("headers").has("Location"))
            throw new AssertionError("Invalid batch definition must return a field error without a polling location");
        var accepted = batch.at("/responses/202");
        if (!accepted.required("description").asString().contains("Location")
                || !accepted.at("/headers/Location/required").asBoolean() || !accepted.at("/links/inspectBatch").isObject())
            throw new AssertionError("Missing batch polling link");
        var author = paths.required("/api/pixiv/user/{userId}/downloads").required("post");
        if (!author.required("description").asString().contains("schedule.pixiv.discovery-not-found")
                || !author.at("/responses/404/description").asString().contains("batch.not-found"))
            throw new AssertionError("Missing distinction between local and upstream 404");
        for (String path : List.of("/api/pixiv/search", "/api/pixiv/search/range",
                "/api/pixiv/novel-search", "/api/pixiv/novel-search/range")) {
            boolean explained = false;
            for (var parameter : paths.required(path).required("get").required("parameters")) {
                if (parameter.path("name").asString().equals("mode")) {
                    String help = parameter.required("description").asString();
                    explained = help.contains("mode=r18") && help.contains("xRestrict");
                }
            }
            if (!explained) throw new AssertionError("Missing rating visibility guidance on " + path);
        }
    }

    private static void verifyOutputReceipt(HttpClient client, HttpRequest.Builder admin, URI base,
            DownloadLifecycleRegistry lifecycle, Path output) throws Exception {
        var owner = new ExternalCapabilityOwner("receipt-fixture", "receipt-fixture", 1, 1);
        var queued = new java.util.concurrent.atomic.AtomicReference<QueueTaskTracker.Task>();
        var handler = new DownloadSubmissionHandler() {
            @Override public String workType() { return "receipt-fixture"; }
            @Override public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
                var task = new QueueTaskTracker(workType()).prepareQueued(null);
                lifecycle.track(attempt, task, null, workType(), "fixture", true, output);
                task.bind(() -> {
                    lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.STARTED));
                    try {
                        Files.createDirectories(output);
                        Files.writeString(output.resolve("result.txt"), "saved");
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                    lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
                });
                if (!queued.compareAndSet(null, task)) throw new AssertionError("Duplicate execution");
            }
        };
        lifecycle.register(owner, new DownloadLifecycleRegistry.Contribution(
                List.of(), List.of(), List.of(), Map.of(handler.workType(), handler)));
        try {
            var command = new DownloadSubmission(UUID.randomUUID(), handler.workType(), "1", Map.of());
            var submit = admin.copy().uri(base.resolve("/api/download/tasks")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(command))).build();
            var first = client.send(submit, HttpResponse.BodyHandlers.ofByteArray());
            if (first.statusCode() != 202) throw new AssertionError("Submit returned " + first.statusCode());
            var receipt = JSON.readTree(first.body()).required("task");
            String path = receipt.required("outputPath").asString();
            if (!path.equals(output.toString()) || !"QUEUED".equals(receipt.required("phase").asString())
                    || Files.exists(output)) throw new AssertionError("Missing planned directory in 202 receipt");
            queued.get().run();
            var status = get(client, admin.copy().uri(base.resolve(first.headers().firstValue("Location").orElseThrow())));
            if (!"COMPLETED".equals(status.required("phase").asString()) || !path.equals(status.required("outputPath").asString())
                    || !"saved".equals(Files.readString(Path.of(path).resolve("result.txt"))))
                throw new AssertionError("Completed task directory does not contain the downloaded file");
            var retry = client.send(submit, HttpResponse.BodyHandlers.ofByteArray());
            var duplicate = JSON.readTree(retry.body());
            if (retry.statusCode() != 202 || !duplicate.required("duplicate").asBoolean()
                    || !status.equals(duplicate.required("task"))) throw new AssertionError("Retry lost the output receipt");
            var history = get(client, admin.copy().uri(base.resolve("/api/download/tasks/history"))).required("tasks");
            for (var item : history) {
                if (item.required("attempt").equals(status.required("attempt")) && path.equals(item.required("outputPath").asString()))
                    return;
            }
            throw new AssertionError("Missing output directory in task history");
        } finally {
            lifecycle.withdraw(owner);
        }
    }

    private static Map<String, String> retiredClassifierRoutes() {
        return Map.of("/api/tools/classifier/settings", "get put", "/api/tools/classifier/folders", "get delete",
                "/api/tools/classifier/images", "get", "/api/tools/classifier/image", "get", "/api/tools/classifier/moves", "post",
                "/api/downloaded/by-move-folder", "get", "/api/downloaded/move/{artworkId}", "post",
                "/api/library/artworks/{artworkId}/move", "post");
    }

    private static void verifyRetiredClassifier(JsonNode document) {
        var paths = document.required("paths");
        for (var route : retiredClassifierRoutes().entrySet()) for (String verb : route.getValue().split(" ")) {
            var op = paths.required(route.getKey()).required(verb);
            var responses = op.required("responses");
            if (!op.path("deprecated").asBoolean() || !responses.has("410") || responses.propertyNames().stream().anyMatch(s -> s.startsWith("2"))
                    || op.has("requestBody") || responses.required("410").has("content"))
                throw new AssertionError("Retired operation " + verb + " " + route.getKey() + ": " + op);
            if (!op.required("description").asString().contains("410"))
                throw new AssertionError("Missing retirement notice for " + route.getKey());
            if (route.getKey().contains("{artworkId}") && op.required("parameters").valueStream().noneMatch(parameter ->
                    parameter.path("name").asString("").equals("artworkId") && parameter.at("/schema/type").asString("").equals("string")
                            && !parameter.path("description").asString("").isBlank()))
                throw new AssertionError("Missing retired artwork path parameter for " + route.getKey());
        }
        for (var route : Map.of("/api/download/tasks/{taskId}", "get", "/api/download/tasks/{taskId}/cancel", "post").entrySet())
            if (!paths.required(route.getKey()).required(route.getValue()).required("responses").required("503").toString()
                    .contains("DOWNLOAD_TASK_RECEIPT_UNAVAILABLE")) throw new AssertionError("Missing receipt failure contract");
    }

    private static void verifyOperationDetails(JsonNode document, String heading) {
        var operations = document.required("paths").required("/api/download/tasks");
        String receiptRef = operations.required("post").at("/responses/202/content/application~1json/schema")
                .required("$ref").asString();
        String taskRef = document.at(receiptRef.substring(1)).at("/properties/task").required("$ref").asString();
        var properties = document.at(taskRef.substring(1)).required("properties");
        var reason = properties.required("failureReason");
        var reasonSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(reason);
        if (!reason.required("description").asString().contains("OUTPUT_FILE_CONFLICT")
                || !reasonSchema.validate(JSON.readTree("\"OUTPUT_FILE_CONFLICT\"")).isEmpty()
                || reasonSchema.validate(JSON.readTree("\"UNKNOWN_TEST_REASON\"")).isEmpty())
            throw new AssertionError("Missing output file conflict in failureReason for " + heading);
        var output = properties.required("outputPath");
        if (output.path("description").asString("").isBlank())
            throw new AssertionError("Missing outputPath documentation in " + heading);
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(output);
        if (!schema.validate(JSON.readTree("null")).isEmpty() || !schema.validate(JSON.readTree("\"/output\"")).isEmpty()
                || schema.validate(JSON.readTree("42")).isEmpty())
            throw new AssertionError("Expected nullable output directory string in " + heading);
        String marker = "\n\n### " + heading + "\n\n";
        for (var entry : Map.of("/api/download/batches", "batch.work.capacity-exceeded",
                "/api/pixiv/user/{userId}/downloads", "schedule.pixiv.discovery-not-found").entrySet()) {
            String body = document.required("paths").required(entry.getKey()).required("post").required("description").asString();
            int headingAt = body.indexOf(marker);
            if (headingAt < 0 || headingAt != body.lastIndexOf(marker) || !body.substring(headingAt).contains(entry.getValue()))
                throw new AssertionError("Missing details on " + entry.getKey() + " for " + heading);
        }
        String description = operations.required("post").required("description").asString();
        int start = description.indexOf(marker);
        if (start < 0 || start != description.lastIndexOf(marker)
                || operations.required("get").required("description").asString().contains(marker))
            throw new AssertionError("Unexpected optional section for " + heading);
        String details = description.substring(start + marker.length());
        var catalog = document.required("x-download-submissions");
        for (var type : List.of("artwork", "novel")) {
            String provider = catalog.required(type).required("description").asString();
            if (!details.contains("#### `" + type + "`\n\n" + provider))
                throw new AssertionError("Missing " + type + " provider details for " + heading);
        }
        for (String term : List.of("core:downloads.create", "duplicate=true", "Location", "task.outputPath", "OUTPUT_ALREADY_REGISTERED",
                "OUTPUT_LAYOUT_CONFLICT", "OUTPUT_FILE_CONFLICT", "SHA-256", "GET /api/library/artworks/{artworkId}/partial", "autoTranslate"))
            if (!details.contains(term)) throw new AssertionError("Missing " + term + " in " + heading);
    }

    private static void verifyMetadataFailures(HttpClient client, HttpRequest.Builder admin, URI base, RestTemplate ajax) throws Exception {
        var upstream = MockRestServiceServer.bindTo(ajax).build();
        String path = "/api/download/tasks";
        for (String type : List.of("artwork", "novel")) {
            String uri = "https://www.pixiv.net/ajax/" + ("artwork".equals(type) ? "illust/551" : "novel/551?lang=zh");
            upstream.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "37").body("private-upstream-fixture"));
            upstream.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.NOT_FOUND));
            var command = new DownloadSubmission(UUID.randomUUID(), type, "551", Map.of());
            var limited = post(client, admin, base, path, command);
            expectError(limited, 429, "DOWNLOAD_TASK_UPSTREAM_RATE_LIMITED", null);
            if (!"37".equals(limited.headers().firstValue("Retry-After").orElse(null))
                    || JSON.readTree(limited.body()).toString().contains("private-upstream-fixture"))
                throw new AssertionError("Lost retry delay or exposed upstream response for " + type);
            var duplicate = post(client, admin, base, path, command);
            var receipt = JSON.readTree(duplicate.body());
            if (duplicate.statusCode() != 202 || !receipt.path("duplicate").asBoolean()
                    || !"UPSTREAM_RATE_LIMITED".equals(receipt.at("/task/failureReason").asString("")))
                throw new AssertionError("Lost rate-limit reason in duplicate receipt for " + type);
            var missing = post(client, admin, base, path, new DownloadSubmission(UUID.randomUUID(), type, "551", Map.of()));
            expectError(missing, 404, "WORK_NOT_FOUND", null);
            var invalid = post(client, admin, base, path, new DownloadSubmission(UUID.randomUUID(), type, "551", Map.of("unexpected", "value")));
            expectError(invalid, 422, "DOWNLOAD_TASK_REJECTED", null);
            if (missing.headers().firstValue("Retry-After").isPresent() || invalid.headers().firstValue("Retry-After").isPresent()
                    || !"/options".equals(JSON.readTree(invalid.body()).path("field").asString("")))
                throw new AssertionError("Mixed lookup and input diagnostics for " + type);
            upstream.verify();
            upstream.reset();
        }
        System.out.println("Artwork and novel HTTP submission metadata contracts passed");
    }

    private static void verifyRejectedSubmission(HttpClient client, HttpRequest.Builder admin, URI base) throws Exception {
        String path = "/api/download/tasks";
        var command = Map.of("requestId", UUID.randomUUID().toString(), "workType", "artwork",
                "workId", "42", "options", Map.of("pages", "0,0"));
        var unknown = new java.util.HashMap<String, Object>(command);
        unknown.put("workType", "unpublished-fixture");
        var unsupported = post(client, admin, base, path, unknown);
        expectError(unsupported, 400, "DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE", null);
        if (!"/workType".equals(JSON.readTree(unsupported.body()).required("field").asString()))
            throw new AssertionError("Unsupported type did not identify workType");
        var first = post(client, admin, base, path, command);
        if (first.statusCode() != 422 || !"DOWNLOAD_TASK_REJECTED".equals(JSON.readTree(first.body()).required("code").asString()))
            throw new AssertionError("Duplicate page selection must reject the submission");
        var reason = JSON.readTree(first.body());
        if (!"/options/pages".equals(reason.required("field").asString()) || !"duplicate".equals(reason.required("reason").asString()))
            throw new AssertionError("Rejected page selection lost its field or reason");
        var retry = post(client, admin, base, path, command);
        var receipt = JSON.readTree(retry.body());
        if (retry.statusCode() != 202 || !receipt.required("duplicate").asBoolean())
            throw new AssertionError("Retry did not identify the original rejected attempt");
        var location = base.resolve(retry.headers().firstValue("Location").orElseThrow());
        var task = get(client, admin.copy().uri(location).GET());
        if (!task.equals(receipt.required("task")) || !"FAILED".equals(task.required("phase").asString())
                || !task.required("queueType").asString().isEmpty() || !task.required("failureReason").isNull()
                || !task.required("outputPath").isNull())
            throw new AssertionError("Expected a failed attempt without a queue binding or output directory");
        var history = get(client, admin.copy().uri(base.resolve(path + "/history")).GET());
        if (history.required("tasks").valueStream().noneMatch(item -> item.required("attempt").equals(task.required("attempt"))))
            throw new AssertionError("Rejected attempt missing from history");
    }

    private static JsonNode get(HttpClient client, HttpRequest.Builder request) throws Exception {
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new AssertionError(response.uri() + " returned " + response.statusCode());
        return JSON.readTree(response.body());
    }

    static LocalArtworkImports.Snapshot await(LocalArtworkImports imports, UUID id,
            LocalArtworkImports.Phase active, LocalArtworkImports.Phase expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        var task = imports.status(id);
        while (task.phase() == active && System.nanoTime() < deadline) {
            Thread.sleep(20);
            task = imports.status(id);
        }
        if (task.phase() != expected) throw new AssertionError(task.phase() + " " + task.problems());
        return task;
    }
}
