package acceptance;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.download.LocalWorkAssetService;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.*;

final class NovelOwnershipAcceptance {
    private final HttpClient client;
    private final HttpRequest.Builder admin;
    private final URI base;
    private final Path root;
    private final JdbcTemplate db;
    private final LocalArtworkImports imports;
    private final ExternalWorkFiles references;
    private final LocalWorkAssetService assets;

    NovelOwnershipAcceptance(HttpClient client, HttpRequest.Builder admin, URI base, ConfigurableApplicationContext app) {
        this.client = client;
        this.admin = admin;
        this.base = base;
        root = Path.of(app.getBean(DownloadConfig.class).getRootFolder());
        db = new JdbcTemplate(app.getBean(DataSource.class));
        imports = app.getBean(LocalArtworkImports.class);
        references = app.getBean(ExternalWorkFiles.class);
        assets = app.getBean(LocalWorkAssetService.class);
    }

    void verify(ConfigurableApplicationContext app) throws Exception {
        Path overlap = legacyNovel(7702);
        byte[] original = Files.readAllBytes(overlap.resolve("picture.png"));
        importImage(702, overlap);
        var denied = delete(7702);
        assertThat(denied.statusCode()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(denied.body()).required("code").asString()).isEqualTo("work.delete.ownership-incomplete");
        var refusedExport = export(7702, true);
        assertThat(refusedExport.statusCode()).isEqualTo(409);
        var error = JsonMapper.shared().readTree(refusedExport.body());
        assertThat(error.required("code").asString()).isEqualTo("work.delete.ownership-incomplete");
        assertThat(error.has("archiveToken")).isFalse();
        var safeZip = readArchive(7702, false);
        assertThat(safeZip).containsOnlyKeys("7702/body.txt", "manifest.json");
        assertThat(new String(safeZip.get("7702/body.txt"), StandardCharsets.UTF_8)).isEqualTo("novel 7702");
        assertThat(new String(safeZip.get("manifest.json"), StandardCharsets.UTF_8)).doesNotContain("picture.png");
        assertActive(7702);
        assertThat(Files.readAllBytes(references.file(WorkType.ARTWORK, 702, 0))).isEqualTo(original);
        assertThat(db.queryForObject("SELECT deleted FROM artworks WHERE artwork_id=702", Integer.class)).isZero();
        assertThat(Files.readString(overlap.resolve("body.txt"))).isEqualTo("novel 7702");

        // The ZIP source set was valid before another work imported one of its files.
        Path late = legacyNovel(7703);
        var archived = archiveHashes(7703);
        assertThat(archived).containsOnlyKeys(late.resolve("body.txt"), late.resolve("picture.png"));
        String token = enqueueBeforeChange(app, 7703, () -> importImage(703, late));
        var retainedZip = readArchive(token, "failed", 1, 0);
        assertThat(retainedZip).containsOnlyKeys("7703/body.txt", "7703/picture.png", "manifest.json");
        assertThat(retainedZip.get("7703/picture.png")).isEqualTo(original);
        assertThatThrownBy(() -> assets.deleteArchivedFiles(WorkType.NOVEL, 7703, archived,
                () -> fail("A conflicting archive must not delete its work record")))
                .isInstanceOf(WorkDeletionException.class);
        assertActive(7703);
        assertThat(Files.readString(late.resolve("body.txt"))).isEqualTo("novel 7703");
        assertThat(Files.readAllBytes(references.file(WorkType.ARTWORK, 703, 0))).isEqualTo(original);

        Path direct = legacyNovel(7704);
        assertThat(delete(7704).statusCode()).isEqualTo(200);
        assertDeleted(7704, direct);
        Path exported = legacyNovel(7705);
        var normalZip = readArchive(7705, true);
        assertThat(normalZip).containsOnlyKeys("7705/body.txt", "7705/picture.png", "manifest.json");
        assertThat(normalZip.get("7705/picture.png")).isEqualTo(original);
        assertDeleted(7705, exported);

        var owners = app.getBean(DownloadDirectories.class);
        for (long id : List.of(7706L, 7707L)) {
            Path staged = legacyNovel(id);
            Runnable commit = () -> {
                assertThatThrownBy(() -> owners.requireImportable(List.of(staged.resolve("picture.png"))))
                        .isInstanceOf(DirectoryTemplateException.class);
                owners.requireImportable(List.of(root.resolve("unrelated.png")));
                app.getBean(NovelMetadataRepository.class).markNovelDeleted(id);
            };
            boolean deleted = id == 7706 ? assets.deleteLocalFiles(WorkType.NOVEL, id, commit)
                    : assets.deleteArchivedFiles(WorkType.NOVEL, id, archiveHashes(id), commit);
            assertThat(deleted).isTrue();
            assertDeleted(id, staged);
            owners.requireImportable(List.of(staged.resolve("picture.png")));
        }
        verifyManagedDirectories(app, owners);
        System.out.println("Legacy novel HTTP and ZIP ownership passed: overlap 409, plain ZIP excludes the imported image, queued ZIP retains its selected image with sourceDeletion=failed (0/1), clean ZIP deletion succeeded (1/1)");
    }

    private void verifyManagedDirectories(ConfigurableApplicationContext app, DownloadDirectories owners) throws Exception {
        long artwork = 817071;
        String name = artwork + "_p0.png";
        Path original = Files.createDirectory(root.resolve(Long.toString(artwork)));
        assertThat(ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", original.resolve(name).toFile())).isTrue();
        var registered = OpenApiResponseAcceptance.post(client, admin, base, "/api/downloaded/" + artwork + "/recover-metadata", Map.of("pageCount", 1));
        assertThat(registered.statusCode()).isEqualTo(200);
        byte[] image = Files.readAllBytes(original.resolve(name));
        long novel = 7710;
        Path dir = root.resolve("managed-novel-" + novel);
        owners.prepare(WorkType.NOVEL, novel, dir);
        writeNovel(novel, dir);
        Path nested = Files.move(original, dir.resolve("illustrations"));
        assertThat(updateDirectory(artwork, nested, true).statusCode()).isEqualTo(204);
        assertArtwork(artwork, image);
        assertThat(delete(novel).statusCode()).isEqualTo(409);
        var refused = export(novel, true);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(JsonMapper.shared().readTree(refused.body()).required("code").asString()).isEqualTo("work.delete.ownership-incomplete");
        assertThat(readArchive(novel, false)).containsOnlyKeys("7710/body.txt", "7710/picture.png", "manifest.json");
        assertActive(novel);
        assertThat(Files.readString(dir.resolve("body.txt"))).isEqualTo("novel " + novel);
        assertArtwork(artwork, image);

        // The current reference protects empty directories too, in either containment direction.
        Path empty = Files.createDirectory(dir.resolve("empty"));
        for (Path location : List.of(empty, dir, root)) {
            assertThat(updateDirectory(artwork, location, true).statusCode()).isEqualTo(204);
            assertThat(delete(novel).statusCode()).isEqualTo(409);
            if (!location.equals(empty)) assertThat(export(novel, false).statusCode()).isEqualTo(204);
            assertThat(empty).isDirectory();
            assertActive(novel);
        }
        // The original folder and its historic claim may remain after the active file has moved away.
        Path independent = Files.move(nested, root.resolve("relocated-illustration"));
        assertThat(updateDirectory(artwork, dir, false).statusCode()).isEqualTo(204);
        assertThat(updateDirectory(artwork, independent, true).statusCode()).isEqualTo(204);
        db.update("INSERT INTO download_directories(claim_id,work_type,work_id,directory) VALUES(?,'ARTWORK',?,?)",
                UUID.randomUUID().toString(), artwork, dir.toString());
        assertThat(readArchive(novel, true)).containsOnlyKeys("7710/body.txt", "7710/picture.png", "manifest.json");
        assertDeleted(novel, dir);
        assertArtwork(artwork, image);

        Path old = legacyNovel(7711);
        try (var reservation = owners.reserveNovelDeletion(7711, old)) {
            for (Path conflict : List.of(old.resolve("illustrations"), old, root)) {
                var rejected = updateDirectory(artwork, conflict, true);
                assertThat(rejected.statusCode()).isEqualTo(409);
                assertThat(JsonMapper.shared().readTree(rejected.body()).required("code").asString()).isEqualTo("DIRECTORY_UPDATE_CONFLICT");
            }
            assertThat(updateDirectory(artwork, independent, true).statusCode()).isEqualTo(204);
        }
        assertThat(updateDirectory(artwork, old.resolve("illustrations"), true).statusCode()).isEqualTo(204);
        assertThat(delete(7711).statusCode()).isEqualTo(409);
        assertThat(updateDirectory(artwork, independent, true).statusCode()).isEqualTo(204);
        assertThat(assets.deleteLocalFiles(WorkType.NOVEL, 7711, () -> {
            try {
                var rejected = updateDirectory(artwork, old.resolve("new-subdirectory"), true);
                assertThat(rejected.statusCode()).isEqualTo(409);
                assertThat(JsonMapper.shared().readTree(rejected.body()).required("code").asString()).isEqualTo("DIRECTORY_UPDATE_CONFLICT");
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            db.update("UPDATE novels SET deleted=1 WHERE novel_id=7711");
        })).isTrue();
        assertDeleted(7711, old);
        assertArtwork(artwork, image);
        Path queued = legacyNovel(7712);
        Path movedBeforeRepair = Files.move(independent, queued.resolve("illustrations"));
        String token = enqueueBeforeChange(app, 7712, () ->
                assertThat(updateDirectory(artwork, movedBeforeRepair, true).statusCode()).isEqualTo(204));
        var retained = readArchive(token, "failed", 1, 0);
        assertThat(retained.values()).anySatisfy(bytes -> assertThat(bytes).isEqualTo(image));
        assertThat(retained).containsKeys("7712/body.txt", "manifest.json");
        assertActive(7712);
        assertThat(queued.resolve("body.txt")).isRegularFile();
        assertArtwork(artwork, image);
        System.out.println("Queued managed novel ZIP retained its pre-registration selection with sourceDeletion=failed (0/1), body and current artwork intact");
        System.out.println("Managed novel HTTP and ZIP ownership passed: physical relocation 204 and raw image intact, foreign tree excluded, direct and export deletion 409, empty/equal/ancestor protected, moved-away and unrelated works delete normally, concurrent directory repair 409 then 204");
    }

    private HttpResponse<byte[]> updateDirectory(long artwork, Path path, boolean moved) throws Exception {
        return OpenApiResponseAcceptance.post(client, admin, base, "/api/tools/directories",
                Map.of("artworkId", artwork, "moved", moved, "path", path.toString()));
    }

    private void assertArtwork(long id, byte[] image) throws Exception {
        var raw = client.send(admin.copy().uri(base.resolve("/api/downloaded/rawfile/" + id + "/0")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(raw.statusCode()).isEqualTo(200);
        assertThat(raw.body()).isEqualTo(image);
        assertThat(db.queryForObject("SELECT deleted FROM artworks WHERE artwork_id=?", Integer.class, id)).isZero();
    }

    @FunctionalInterface
    private interface PendingChange { void run() throws Exception; }

    private String enqueueBeforeChange(ConfigurableApplicationContext app, long novel, PendingChange change) throws Exception {
        var pool = app.getBean("archiveTaskExecutor", org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        var occupied = new java.util.concurrent.CountDownLatch(pool.getMaxPoolSize());
        var resume = new java.util.concurrent.CountDownLatch(1);
        try {
            for (int i = 0; i < pool.getMaxPoolSize(); i++) pool.execute(() -> {
                occupied.countDown();
                try { resume.await(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
            assertThat(occupied.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var request = export(novel, true);
            assertThat(request.statusCode()).isEqualTo(200);
            String token = JsonMapper.shared().readTree(request.body()).required("archiveToken").asString();
            assertThat(archiveStatus(token).required("sourceDeletion").required("status").asString()).isEqualTo("pending");
            change.run();
            return token;
        } finally { resume.countDown(); }
    }

    private Map<Path, String> archiveHashes(long id) throws Exception {
        var hashes = new LinkedHashMap<Path, String>();
        for (Path path : assets.archiveSources(WorkType.NOVEL, id))
            hashes.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        return hashes;
    }

    private Path legacyNovel(long id) throws Exception {
        Path dir = Files.createDirectory(root.resolve("novel-" + id));
        writeNovel(id, dir);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM download_directories WHERE work_type='NOVEL' AND work_id=?", Integer.class, id)).isZero();
        return dir;
    }

    private void writeNovel(long id, Path dir) throws Exception {
        Files.writeString(dir.resolve("body.txt"), "novel " + id);
        assertThat(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", dir.resolve("picture.png").toFile())).isTrue();
        db.update("INSERT INTO novels(novel_id,title,folder,count,extensions,time,file_base_name) VALUES(?,?,?,1,'txt',?,'body')",
                id, "Novel " + id, dir.toString(), id);
    }

    private void importImage(long artwork, Path folder) throws Exception {
        String template = root.toString().replace('\\', '/') + "/novel-7{artwork_id}/picture.{extension}";
        var preview = imports.scan(new LocalArtworkImports.Request(List.of(template), List.of(artwork), true, folder.resolve("picture.png").toString()));
        OpenApiResponseAcceptance.await(imports, preview.id(), LocalArtworkImports.Phase.SCANNING, LocalArtworkImports.Phase.READY);
        assertThat(imports.items(preview.id(), 0, 10)).singleElement().satisfies(item -> {
            assertThat(item.artworkId()).isEqualTo(artwork);
            assertThat(item.disposition()).isEqualTo(LocalArtworkImports.Disposition.NEW);
        });
        imports.start(preview.id());
        assertThat(OpenApiResponseAcceptance.await(imports, preview.id(), LocalArtworkImports.Phase.IMPORTING, LocalArtworkImports.Phase.COMPLETED).imported()).isOne();
        assertThat(references.file(WorkType.ARTWORK, artwork, 0)).isEqualTo(folder.resolve("picture.png"));
    }

    private HttpResponse<byte[]> delete(long id) throws Exception {
        return client.send(admin.copy().uri(base.resolve("/api/gallery/novel/" + id)).DELETE().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> export(long id, boolean delete) throws Exception {
        return OpenApiResponseAcceptance.post(client, admin, base, "/api/gallery/novels/export",
                Map.of("mode", "ids", "ids", List.of(id), "format", "zip", "groupBy", "id", "deleteAfter", delete));
    }

    private Map<String, byte[]> readArchive(long id, boolean delete) throws Exception {
        var result = export(id, delete);
        assertThat(result.statusCode()).isEqualTo(200);
        String token = JsonMapper.shared().readTree(result.body()).required("archiveToken").asString();
        return readArchive(token, delete ? "succeeded" : "not_requested", delete ? 1 : 0, delete ? 1 : 0);
    }

    private JsonNode archiveStatus(String token) throws Exception {
        var response = client.send(admin.copy().uri(base.resolve("/api/archive/status/" + token)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.shared().readTree(response.body());
    }

    private Map<String, byte[]> readArchive(String token, String expected, int requested, int deleted) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode status;
        do {
            status = archiveStatus(token);
            assertThat(status.required("status").asString()).isIn("pending", "creating", "ready");
            if ("ready".equals(status.required("status").asString())) {
                var deletion = status.required("sourceDeletion").required("status").asString();
                if (expected.equals(deletion)) break;
                assertThat(deletion).isIn("pending", "running");
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(status.required("status").asString()).isEqualTo("ready");
        var deletion = status.required("sourceDeletion");
        assertThat(deletion.required("status").asString()).isEqualTo(expected);
        assertThat(deletion.required("requestedWorks").asInt()).isEqualTo(requested);
        assertThat(deletion.required("deletedWorks").asInt()).isEqualTo(deleted);
        var zip = client.send(admin.copy().uri(base.resolve("/api/archive/download/" + token)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(zip.statusCode()).isEqualTo(200);
        var entries = new LinkedHashMap<String, byte[]>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) entries.put(entry.getName(), in.readAllBytes());
        }
        return entries;
    }

    private void assertActive(long id) {
        assertThat(db.queryForObject("SELECT deleted FROM novels WHERE novel_id=?", Integer.class, id)).isZero();
    }

    private void assertDeleted(long id, Path folder) {
        assertThat(db.queryForObject("SELECT deleted FROM novels WHERE novel_id=?", Integer.class, id)).isOne();
        assertThat(folder).doesNotExist();
    }
}
