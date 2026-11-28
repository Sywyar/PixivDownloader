package acceptance;

import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadHistory;
import top.sywyar.pixivdownload.core.artwork.download.PartialArtwork;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import java.util.UUID;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, SQLite and image files. Only the encoder process is replaced by ImageIO. */
final class ArtworkRecoveryAcceptance {
    private final HttpClient client;
    private final HttpRequest.Builder admin;
    private final URI base;
    private final Path root;
    private final ArtworkMediaStore media;
    private final PixivDatabase database;

    ArtworkRecoveryAcceptance(HttpClient client, HttpRequest.Builder admin, URI base, ConfigurableApplicationContext app) {
        this.client = client;
        this.admin = admin;
        this.base = base;
        this.root = Path.of(app.getBean(DownloadConfig.class).getRootFolder());
        this.media = app.getBean(ArtworkMediaStore.class);
        this.database = app.getBean(PixivDatabase.class);
    }

    void verify(ConfigurableApplicationContext app, Path repo) throws Exception {
        long mixed = 840001, uniform = 840002, converted = 840003, downloaded = 840004;
        for (long id : List.of(mixed, uniform, converted)) {
            String second = id == uniform ? "jpg" : "png";
            writeImages(id, second);
            assertThat(recover(id).statusCode()).isEqualTo(200);
            assertThat(database.getArtworkFileNames(id)).containsExactly(id + "_p0", id + "_p1");
            assertRecovered(id, 0, "jpg");
            assertRecovered(id, 1, second);
            if (id == converted) break;
            Path unrelated = image(id, 0, "webp");
            Files.writeString(unrelated, "independent");
            assertThat(app.getBean(ArtworkFileLocator.class).resolveImageFile(database.getArtwork(id), 0).toPath())
                    .isEqualTo(image(id, 0, "jpg"));
            assertDeleted(id, second);
            assertThat(Files.readString(unrelated)).isEqualTo("independent");
        }

        writeImages(downloaded, "png");
        // The fixture belongs to the workbench tests and calls its typed processing paths inside its own class loader.
        var plugin = app.getBean(ExternalPluginContextManager.class).contextFor("download-workbench").orElseThrow();
        Path tests = Path.of(System.getProperty("download-workbench.test.classes",
                repo.resolve("pixivdownload-plugin-download-workbench/target/test-classes").toString()));
        try (var loader = new URLClassLoader(new URL[]{tests.toUri().toURL()}, plugin.getClassLoader())) {
            var fixture = loader.loadClass("acceptance.ArtworkRecoveryMediaFixture");
            fixture.getMethod("processImages", ArtworkMediaStore.class, FileTransactionService.class, DownloadPathGuard.class, Path.class, Path.class)
                    .invoke(null, media, app.getBean(FileTransactionService.class), app.getBean(DownloadPathGuard.class), root.resolve("" + converted), root.resolve("" + downloaded));
        }
        assertThat(media.find(converted, 0)).contains(new ArtworkMediaManifest(ArtworkMediaManifest.UNKNOWN_ORIGINAL,
                List.of("jpg", "png"), false, false));
        assertRecovered(converted, 1, "png");
        assertThat(ImageIO.read(image(converted, 0, "png").toFile())).isNotNull();
        assertDeleted(converted, "png");
        assertThat(image(converted, 0, "png")).doesNotExist();

        for (int page = 0; page < 2; page++) {
            String format = page == 0 ? "jpg" : "png";
            assertThat(media.find(downloaded, page)).contains(new ArtworkMediaManifest(format, List.of(format)));
        }
        app.getBean(ArtworkDownloadHistory.class).record(new ArtworkDownloadCompletion(downloaded, "normal", root.resolve("" + downloaded),
                2, Set.of("jpg", "png"), database.getUniqueTime(), 0, false, null, "", "{artwork_id}_p{page}",
                null, null, null, List.of(), 180, List.of(downloaded + "_p0", downloaded + "_p1")));
        assertDeleted(downloaded, "png");

        long ambiguous = 840005;
        writeImages(ambiguous, "png");
        Files.copy(image(ambiguous, 1, "png"), image(ambiguous, 0, "png"));
        assertThat(recover(ambiguous).statusCode()).isEqualTo(400);
        assertThat(database.getArtwork(ambiguous)).isNull();
        assertThat(media.find(ambiguous, 0)).isEmpty();
        assertThat(image(ambiguous, 0, "png")).exists();

        long legacy = 840006;
        writeImages(legacy, "png");
        database.insertArtwork(InsertArtworkArgument.builder().artworkId(legacy).title("legacy").folder(root.resolve("" + legacy).toString())
                .count(2).extensions("jpg,png").time(database.getUniqueTime()).build());
        assertThat(media.find(legacy, 0)).isEmpty();
        assertThat(media.find(legacy, 1)).isEmpty();
        assertLegacyDeletionRefused(legacy);
        var original = new ArtworkMediaManifest("jpg", List.of("jpg", "png"));
        media.save(legacy, 0, original);
        Files.copy(image(legacy, 1, "png"), image(legacy, 0, "png"));
        assertThat(recover(legacy).statusCode()).isEqualTo(200);
        assertThat(media.find(legacy, 0)).contains(original);
        assertThat(media.find(legacy, 1)).isEmpty();
        assertLegacyDeletionRefused(legacy);
        assertThat(image(legacy, 0, "png")).exists();
        assertThat(media.find(legacy, 0)).contains(original);
        verifyImportOwnership(app);
        verifyCorrectedDirectoryDeletion(app);
        verifyPartialPageDeletion(app);
        System.out.println("Artwork recovery and deletion passed for mixed, uniform, supplemented and downloaded media; ambiguous files preserved; legacy mixed records with zero or one known page returned HTTP 409");
    }

    private void verifyCorrectedDirectoryDeletion(ConfigurableApplicationContext app) throws Exception {
        long corrected = 840009, neighbor = 840010, distinct = 840011;
        Path oldPage = registerJpeg(app, corrected, "p0", 2);
        Path neighborPage = registerJpeg(app, neighbor, "p0", 3);
        byte[] oldBytes = Files.readAllBytes(oldPage);
        byte[] neighborBytes = Files.readAllBytes(neighborPage);
        Path shared = neighborPage.getParent();
        correctDirectory(corrected, shared);

        var rejected = delete(corrected);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(rejected.body()).required("code").asString())
                .isEqualTo("work.delete.ownership-incomplete");
        for (long id : List.of(corrected, neighbor)) {
            assertThat(database.getArtwork(id).deleted()).isFalse();
            assertThat(database.getArtworkFileNames(id)).containsExactly("p0");
            assertThat(media.find(id, 0)).contains(new ArtworkMediaManifest("jpg", List.of("jpg")));
        }
        assertThat(Files.readAllBytes(oldPage)).isEqualTo(oldBytes);
        assertRawJpeg(neighbor, neighborBytes);

        Path distinctPage = registerJpeg(app, distinct, "q0", 4);
        Path relocated = Files.move(distinctPage, shared.resolve(distinctPage.getFileName()));
        correctDirectory(distinct, shared);
        assertThat(delete(distinct).statusCode()).isEqualTo(200);
        assertThat(relocated).doesNotExist();
        assertThat(database.getArtwork(distinct).deleted()).isTrue();
        assertThat(Files.readAllBytes(neighborPage)).isEqualTo(neighborBytes);
        assertRawJpeg(neighbor, neighborBytes);
    }

    private void verifyPartialPageDeletion(ConfigurableApplicationContext app) throws Exception {
        long partialId = 840012, overlapping = 840013, distinct = 840014;
        Path directory = root.resolve(Long.toString(partialId));
        var layout = new PartialArtwork(partialId, directory.toString(), 2, "p{page}", 180, "original",
                "revision", List.of("p0", "p1"), Map.of(), Map.of());
        assertThat(app.getBean(DownloadDirectories.class).prepareIf(WorkType.ARTWORK, partialId, directory,
                () -> database.startPartialArtwork(layout))).isTrue();
        Path saved = directory.resolve("p0.jpg");
        assertThat(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpg", saved.toFile())).isTrue();
        var manifest = new ArtworkMediaManifest("jpg", List.of("jpg"));
        media.savePartial(partialId, 0, manifest, PartialArtwork.fingerprint(directory.resolve("p0"), manifest));
        var progress = database.partialArtwork(partialId);
        assertThat(progress.media()).containsOnlyKeys(0);
        byte[] savedBytes = Files.readAllBytes(saved);

        Path original = registerJpeg(app, overlapping, "p0", 2);
        correctDirectory(overlapping, directory);
        var denied = delete(overlapping);
        assertThat(denied.statusCode()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(denied.body()).required("code").asString())
                .isEqualTo("work.delete.ownership-incomplete");
        assertThat(original).exists();
        assertThat(database.getArtwork(overlapping).deleted()).isFalse();
        assertThat(database.getArtworkFileNames(overlapping)).containsExactly("p0");
        assertThat(media.find(overlapping, 0)).contains(manifest);
        assertThat(Files.readAllBytes(saved)).isEqualTo(savedBytes);
        assertThat(database.partialArtwork(partialId)).isEqualTo(progress);

        Path independent = registerJpeg(app, distinct, "q0", 4);
        Path relocated = Files.move(independent, directory.resolve(independent.getFileName()));
        correctDirectory(distinct, directory);
        assertThat(delete(distinct).statusCode()).isEqualTo(200);
        assertThat(relocated).doesNotExist();
        assertThat(database.getArtwork(distinct).deleted()).isTrue();
        assertThat(database.getArtwork(partialId)).isNull();
        assertThat(database.partialArtwork(partialId)).isEqualTo(progress);
        assertThat(progress.verifiedFiles()).containsExactlyEntriesOf(Map.of(0, List.of("p0.jpg")));
    }

    private Path registerJpeg(ConfigurableApplicationContext app, long id, String baseName, int width) throws Exception {
        Path directory = root.resolve(Long.toString(id));
        app.getBean(DownloadDirectories.class).prepare(WorkType.ARTWORK, id, directory);
        Path page = directory.resolve(baseName + ".jpg");
        assertThat(ImageIO.write(new BufferedImage(width, 2, BufferedImage.TYPE_INT_RGB), "jpg", page.toFile())).isTrue();
        media.save(id, 0, new ArtworkMediaManifest("jpg", List.of("jpg")));
        app.getBean(ArtworkDownloadHistory.class).record(new ArtworkDownloadCompletion(id, "illustration", directory,
                1, Set.of("jpg"), database.getUniqueTime(), 0, false, null, "", baseName, null,
                null, null, List.of(), 180, List.of(baseName)));
        return page;
    }

    private void correctDirectory(long id, Path directory) throws Exception {
        var result = OpenApiResponseAcceptance.post(client, admin, base, "/api/tools/directories",
                Map.of("artworkId", id, "moved", false, "path", directory.toString()));
        assertThat(result.statusCode()).isEqualTo(204);
        assertThat(Path.of(database.getArtwork(id).effectiveFolder())).isEqualTo(directory);
    }

    private void assertRawJpeg(long id, byte[] expected) throws Exception {
        var raw = client.send(admin.copy().uri(base.resolve("/api/downloaded/rawfile/" + id + "/0")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(raw.statusCode()).isEqualTo(200);
        assertThat(raw.body()).isEqualTo(expected);
    }

    private void verifyImportOwnership(ConfigurableApplicationContext app) throws Exception {
        var imports = app.getBean(LocalArtworkImports.class);
        var references = app.getBean(ExternalWorkFiles.class);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(app.getBean(javax.sql.DataSource.class));
        long imported = 840007, candidate = 1840007;
        writeImages(candidate, "png");
        Path source = image(candidate, 0, "jpg");
        byte[] original = Files.readAllBytes(source);
        UUID preview = previewImport(imports, imported, candidate);
        imports.start(preview);
        assertThat(OpenApiResponseAcceptance.await(imports, preview, LocalArtworkImports.Phase.IMPORTING,
                LocalArtworkImports.Phase.COMPLETED).imported()).isOne();
        var rejected = recover(candidate);
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(JsonMapper.shared().readTree(rejected.body()).required("code").asString()).isEqualTo("directory-template.occupied");
        assertThat(delete(candidate).statusCode()).isEqualTo(404);
        assertThat(database.getArtwork(candidate)).isNull();
        assertThat(media.find(candidate, 0)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM download_directories WHERE work_id=?", Integer.class, candidate)).isZero();
        assertThat(database.getArtwork(imported).deleted()).isFalse();
        assertThat(references.file(WorkType.ARTWORK, imported, 0)).isEqualTo(source);
        assertThat(Files.readAllBytes(source)).isEqualTo(original);

        // A pre-existing conflicting managed record must also preserve the external page.
        database.insertArtwork(InsertArtworkArgument.builder().artworkId(candidate).title("legacy overlap")
                .folder(source.getParent().toString()).count(1).extensions("jpg").time(database.getUniqueTime()).build());
        var legacyDelete = delete(candidate);
        assertThat(legacyDelete.statusCode()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(legacyDelete.body()).required("code").asString()).isEqualTo("work.delete.ownership-incomplete");
        assertThat(database.getArtwork(candidate).deleted()).isFalse();
        assertThat(Files.readAllBytes(references.file(WorkType.ARTWORK, imported, 0))).isEqualTo(original);

        long laterImport = 840008, recovered = 1840008;
        writeImages(recovered, "png");
        UUID earlierPreview = previewImport(imports, laterImport, recovered);
        assertThat(recover(recovered).statusCode()).isEqualTo(200);
        imports.start(earlierPreview);
        var result = OpenApiResponseAcceptance.await(imports, earlierPreview, LocalArtworkImports.Phase.IMPORTING,
                LocalArtworkImports.Phase.COMPLETED);
        assertThat(result.failed()).isOne();
        assertThat(result.problems()).extracting(LocalArtworkImports.Problem::code).containsExactly("IMPORT_MANAGED_DIRECTORY");
        assertThat(database.getArtwork(laterImport)).isNull();
        assertThat(references.contains(WorkType.ARTWORK, laterImport)).isFalse();
        assertDeleted(recovered, "png");
        System.out.println("Template import ownership passed: import-first recovery HTTP 400, deletion HTTP 404; legacy overlap HTTP 409; recovery-first rolls back stale preview import");
    }

    private UUID previewImport(LocalArtworkImports imports, long imported, long candidate) throws Exception {
        String template = root.toString().replace('\\', '/') + "/1{artwork_id}/" + candidate + "_p{page}.{extension}";
        var preview = imports.scan(new LocalArtworkImports.Request(List.of(template), List.of(imported), true,
                image(candidate, 0, "jpg").toString()));
        OpenApiResponseAcceptance.await(imports, preview.id(), LocalArtworkImports.Phase.SCANNING, LocalArtworkImports.Phase.READY);
        assertThat(imports.items(preview.id(), 0, 10)).singleElement().satisfies(item -> {
            assertThat(item.artworkId()).isEqualTo(imported);
            assertThat(item.disposition()).isEqualTo(LocalArtworkImports.Disposition.NEW);
            assertThat(item.files()).singleElement().satisfies(file ->
                    assertThat(Path.of(file.path())).isEqualTo(image(candidate, 0, "jpg")));
        });
        return preview.id();
    }

    private void assertLegacyDeletionRefused(long id) throws Exception {
        var refusal = delete(id);
        assertThat(refusal.statusCode()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(refusal.body()).required("code").asString()).isEqualTo("work.delete.ownership-incomplete");
        assertThat(image(id, 0, "jpg")).exists();
        assertThat(image(id, 1, "png")).exists();
        assertThat(database.getArtwork(id).deleted()).isFalse();
    }

    private void writeImages(long id, String second) throws Exception {
        Files.createDirectory(root.resolve("" + id));
        var pixels = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.write(pixels, "jpg", image(id, 0, "jpg").toFile())).isTrue();
        assertThat(ImageIO.write(pixels, second, image(id, 1, second).toFile())).isTrue();
    }

    private Path image(long id, int page, String extension) {
        return root.resolve("" + id).resolve(id + "_p" + page + "." + extension);
    }

    private HttpResponse<String> recover(long id) throws Exception {
        return client.send(admin.copy().uri(base.resolve("/api/downloaded/" + id + "/recover-metadata"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"pageCount\":2}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(long id) throws Exception {
        return client.send(admin.copy().uri(base.resolve("/api/gallery/artwork/" + id)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertRecovered(long id, int page, String format) throws Exception {
        assertThat(media.find(id, page)).contains(new ArtworkMediaManifest(ArtworkMediaManifest.UNKNOWN_ORIGINAL, List.of(format), false, false));
    }

    private void assertDeleted(long id, String second) throws Exception {
        var result = delete(id);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.shared().readTree(result.body()).required("deleted").asInt()).isOne();
        assertThat(image(id, 0, "jpg")).doesNotExist();
        assertThat(image(id, 1, second)).doesNotExist();
        assertThat(database.getArtwork(id).deleted()).isTrue();
    }
}
