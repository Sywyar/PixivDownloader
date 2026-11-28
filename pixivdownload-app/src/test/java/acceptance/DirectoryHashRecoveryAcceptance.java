package acceptance;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.i18n.AppMessages;
import org.springframework.transaction.PlatformTransactionManager;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.hash.*;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

final class DirectoryHashRecoveryAcceptance {
    private final HttpClient client;
    private final HttpRequest.Builder admin;
    private final URI base;
    private final Path root;
    private final JdbcTemplate db;
    private final PixivDatabase library;
    private final ArtworkMediaStore media;
    private final ArtworkHashIndexMaintenance index;

    DirectoryHashRecoveryAcceptance(HttpClient client, HttpRequest.Builder admin, URI base, ConfigurableApplicationContext app) {
        this.client = client;
        this.admin = admin;
        this.base = base;
        root = Path.of(app.getBean(DownloadConfig.class).getRootFolder());
        db = new JdbcTemplate(app.getBean(DataSource.class));
        library = app.getBean(PixivDatabase.class);
        media = app.getBean(ArtworkMediaStore.class);
        index = app.getBean(ArtworkHashIndexMaintenance.class);
    }

    void verify(ConfigurableApplicationContext app) throws Exception {
        long missing = 955101, changed = 955102, unchanged = 955103;
        Path absent = root.resolve("missing-" + missing);
        register(missing, absent);
        assertThat(index.rebuildArtwork(missing)).hasValue(0);
        assertThat(pages(missing)).containsExactly(-2, -1);
        Path found = pageDirectory("found-" + missing, false);

        Path original = pageDirectory("before-" + changed, false);
        Path replacement = pageDirectory("after-" + changed, true);
        register(changed, original);
        assertThat(index.rebuildArtwork(changed)).hasValue(1);
        var oldHash = ImageHasher.hash(original.resolve("page.png")).orElseThrow();
        var newHash = ImageHasher.hash(replacement.resolve("page.png")).orElseThrow();
        assertThat(oldHash).isNotEqualTo(newHash);
        assertHashes(changed, oldHash);
        register(unchanged, root.resolve("absent-" + unchanged));
        assertThat(index.rebuildArtwork(unchanged)).hasValue(0);
        assertThat(index.artworkIdsMissingHashes(Integer.MAX_VALUE)).doesNotContain(missing, changed, unchanged);

        update(missing, false, found);
        assertThat(pages(missing)).isEmpty();
        repairWhileAnOldScanIsComputing(app, changed, replacement);
        assertThat(pages(changed)).isEmpty();
        assertThat(pages(unchanged)).containsExactly(-2, -1);
        assertThat(index.artworkIdsMissingHashes(Integer.MAX_VALUE)).contains(missing, changed).doesNotContain(unchanged);

        scanMissing();
        assertHashes(missing, ImageHasher.hash(found.resolve("page.png")).orElseThrow());
        assertHashes(changed, newHash);
        assertThat(pages(unchanged)).containsExactly(-2, -1);
        // The active moved folder takes precedence. A different spelling of the same directory keeps its hashes.
        update(changed, false, original.resolveSibling("historical"));
        update(changed, true, replacement.resolve("."));
        assertHashes(changed, newHash);
        System.out.println("Directory HTTP and incremental hash scan passed: missing markers and valid old hashes cleared, "
                + "late old rebuild serialized, both replacements indexed from current pixels, unchanged records preserved");
        verifyPrefixMigration(app);
    }

    private void verifyPrefixMigration(ConfigurableApplicationContext app) throws Exception {
        long absent = 956101, changed = 956102, racing = 956103, stable = 956104, excluded = 956105, retained = 956106;
        var paths = app.getBean(PathPrefixCodec.class);
        var directories = app.getBean(DownloadDirectories.class);
        Path before = Files.createDirectory(root.resolve("prefix-before"));
        Path after = Files.createDirectory(root.resolve("prefix-after"));
        Path external = pageDirectory("prefix-before/imported", false);
        Path externalFile = external.resolve("page.png");
        long prefix = paths.forceCreatePrefixId(before.toString());
        long files = paths.forceCreatePrefixId(external.toString());
        Path oldPage = pageDirectory("prefix-before/good", false).resolve("page.png");
        Path newPage = pageDirectory("prefix-after/good", true).resolve("page.png");
        Path recoveredPage = pageDirectory("prefix-after/missing", false).resolve("page.png");
        register(absent, before.resolve("missing"));
        register(changed, oldPage.getParent());
        register(racing, oldPage.getParent());
        register(stable, newPage.getParent());
        register(excluded, before);
        register(retained, before);
        externalReference(excluded, "{" + prefix + "}", "{" + files + "}/page.png");
        externalReference(retained, root.toString(), "{" + files + "}/page.png");
        assertThat(index.rebuildArtwork(absent)).hasValue(0);
        var original = ImageHasher.hash(oldPage).orElseThrow();
        var replacement = ImageHasher.hash(newPage).orElseThrow();
        var imported = ImageHasher.hash(externalFile).orElseThrow();
        assertThat(original).isNotEqualTo(replacement);
        for (long id : List.of(changed, stable, excluded, retained)) assertThat(index.rebuildArtwork(id)).hasValue(1);
        long stableTime = hashTime(stable), retainedTime = hashTime(retained), changedTime = hashTime(changed);

        var registration = OpenApiResponseAcceptance.post(client, admin, base, "/api/tools/path-prefixes",
                Map.of("registerPaths", List.of(Files.createDirectory(root.resolve("prefix-register-only")).toString())));
        assertThat(registration.statusCode()).isEqualTo(200);
        assertThat(changePrefix(prefix, before.resolve(".")).statusCode()).isEqualTo(200);
        assertThat(hashTime(changed)).isEqualTo(changedTime);
        assertThat(raw(excluded).statusCode()).isEqualTo(200);
        assertThat(changePrefix(prefix, before).statusCode()).isEqualTo(200);
        assertThat(hashTime(changed)).isEqualTo(changedTime);
        assertThat(pages(absent)).containsExactly(-2, -1);
        for (Path reserved : List.of(oldPage, newPage)) {
            try (var ignored = directories.reserveDeletion(List.of(reserved))) {
                var blocked = changePrefix(prefix, after);
                assertThat(blocked.statusCode()).as(new String(blocked.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(400);
                var error = JsonMapper.shared().readTree(blocked.body());
                assertThat(error.required("code").asString()).isEqualTo("PATH_PREFIX_REJECTED");
                assertThat(error.required("errors").get(0).required("reason").asString()).isEqualTo("conflict");
                assertThat(hashTime(changed)).isEqualTo(changedTime);
            }
        }

        db.execute("CREATE TRIGGER reject_prefix_move BEFORE UPDATE ON path_prefixes WHEN OLD.id=" + prefix
                + " BEGIN SELECT RAISE(ABORT, 'prefix test'); END");
        try {
            assertThat(changePrefix(prefix, after).statusCode()).isEqualTo(500);
            assertThat(hashTime(changed)).isEqualTo(changedTime);
            assertThat(pages(absent)).containsExactly(-2, -1);
            assertThat(Path.of(paths.resolve("{" + prefix + "}/good"))).isEqualTo(oldPage.getParent());
        } finally {
            db.execute("DROP TRIGGER reject_prefix_move");
        }

        var located = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var locator = spy(app.getBean(ArtworkFileLocator.class));
        doAnswer(call -> {
            var chosen = call.callRealMethod();
            if (count.getAndIncrement() == 0) {
                located.countDown();
                assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return chosen;
        }).when(locator).resolveHashSourceFile(any(), eq(0));
        var mapper = app.getBean(ImageHashMapper.class);
        var worker = new ArtworkHashIndexAdapter(mapper, library, new ArtworkHashService(mapper, locator,
                app.getBean(AppMessages.class), app.getBean(PlatformTransactionManager.class),
                app.getBean(ArtworkMediaDecoder.class), paths));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> worker.rebuildArtwork(racing));
            try {
                assertThat(located.await(10, TimeUnit.SECONDS)).isTrue();
                var moved = changePrefix(prefix, after);
                assertThat(moved.statusCode()).as(new String(moved.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
                assertThat(JsonMapper.shared().readTree(moved.body()).required("applied").asInt()).isEqualTo(1);
            } finally { resume.countDown(); }
            assertThat(pending.get(10, TimeUnit.SECONDS)).hasValue(1);
            assertThat(count.get()).isEqualTo(2);
            assertHashes(racing, replacement);
        }

        assertThat(pages(changed)).isEmpty();
        assertThat(pages(absent)).isEmpty();
        assertThat(pages(excluded)).isEmpty();
        assertThat(hashTime(stable)).isEqualTo(stableTime);
        assertThat(hashTime(retained)).isEqualTo(retainedTime);
        assertThat(index.artworkIdsMissingHashes(Integer.MAX_VALUE)).contains(absent, changed, excluded).doesNotContain(stable, retained, racing);
        assertThat(raw(changed).body()).containsExactly(Files.readAllBytes(newPage));
        assertThat(raw(absent).body()).containsExactly(Files.readAllBytes(recoveredPage));
        assertThat(raw(excluded).statusCode()).isEqualTo(404);
        assertThat(raw(retained).body()).containsExactly(Files.readAllBytes(externalFile));
        scanMissing();
        assertHashes(absent, ImageHasher.hash(recoveredPage).orElseThrow());
        assertHashes(changed, replacement);
        assertHashes(stable, replacement);
        assertHashes(retained, imported);
        assertThat(hashTime(retained)).isEqualTo(retainedTime);
        assertThat(pages(excluded)).containsExactly(-2, -1);
        verifySameDirectoryAlias(paths);
        System.out.println("Prefix HTTP and incremental hash scan passed: positive and missing rows invalidated, original HTTP bytes matched new pixels, "
                + "late writer reread, cross-prefix read-only containment changed, unaffected rows and rollback preserved");
    }

    private void verifySameDirectoryAlias(PathPrefixCodec paths) throws Exception {
        long imported = 956107, missing = 956108;
        Path parent = Files.createDirectory(root.resolve("prefix-alias"));
        Path actual = pageDirectory("prefix-alias/actual", false);
        Path alias = parent.resolve("alias");
        if (java.io.File.separatorChar == '\\') {
            // mklink /J creates a local directory junction without elevation. Both names are fixed and relative to this fixture.
            var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/j", "alias", "actual")
                    .directory(parent.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor()).as("create test junction").isZero();
        } else {
            Files.createSymbolicLink(alias, actual);
        }
        assertThat(Files.isSameFile(actual, alias)).isTrue();
        long prefix = paths.forceCreatePrefixId(actual.toString());
        register(missing, actual.resolve("absent/subdirectory"));
        assertThat(index.rebuildArtwork(missing)).hasValue(0);
        register(imported, actual);
        externalReference(imported, "{" + prefix + "}", actual.resolve("page.png").toString());
        assertThat(index.rebuildArtwork(imported)).hasValue(1);
        var expected = ImageHasher.hash(actual.resolve("page.png")).orElseThrow();
        assertHashes(imported, expected);
        assertThat(changePrefix(prefix, alias).statusCode()).isEqualTo(200);
        assertThat(raw(imported).statusCode()).isEqualTo(404);
        assertThat(pages(missing)).containsExactly(-2, -1);
        assertThat(pages(imported)).isEmpty();
        scanMissing();
        assertThat(pages(imported)).containsExactly(-2, -1);
        assertThat(changePrefix(prefix, actual).statusCode()).isEqualTo(200);
        assertThat(pages(missing)).containsExactly(-2, -1);
        assertThat(pages(imported)).isEmpty();
        scanMissing();
        assertHashes(imported, expected);
        assertThat(raw(imported).body()).containsExactly(Files.readAllBytes(actual.resolve("page.png")));
    }

    private void externalReference(long id, String rootPath, String filePath) {
        db.update("INSERT INTO external_work_files(reference_id,work_type,work_id,page,root_path,file_path,record_time) "
                + "SELECT ?, 'ARTWORK', artwork_id, 0, ?, ?, time FROM artworks WHERE artwork_id=?",
                "prefix-hash-" + id, rootPath, filePath, id);
    }

    private long hashTime(long id) {
        return db.queryForObject("SELECT created_time FROM artwork_image_hashes WHERE artwork_id=? AND page=0", Long.class, id);
    }

    private HttpResponse<byte[]> changePrefix(long id, Path destination) throws Exception {
        return OpenApiResponseAcceptance.post(client, admin, base, "/api/tools/path-prefixes",
                Map.of("updates", List.of(Map.of("id", id, "path", destination.toString()))));
    }

    private HttpResponse<byte[]> raw(long id) throws Exception {
        return client.send(admin.copy().uri(base.resolve("/api/downloaded/rawfile/" + id + "/0")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private void scanMissing() throws Exception {
        var start = OpenApiResponseAcceptance.post(client, admin, base, "/api/duplicates/scan?force=false", Map.of());
        assertThat(start.statusCode()).isEqualTo(200);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            var state = client.send(admin.copy().uri(base.resolve("/api/duplicates/scan/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(state.statusCode()).isEqualTo(200);
            var phase = JsonMapper.shared().readTree(state.body()).required("state").asString();
            if (phase.equals("DONE")) break;
            assertThat(phase).isEqualTo("RUNNING");
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(30);
        }
    }

    private void repairWhileAnOldScanIsComputing(ConfigurableApplicationContext app, long id, Path directory) throws Exception {
        var started = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var writer = spy(app.getBean(ArtworkHashService.class));
        doAnswer(call -> {
            started.countDown();
            assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(writer).recordArtworkHashes(org.mockito.ArgumentMatchers.eq(id), any());
        var scan = new ArtworkHashIndexAdapter(app.getBean(ImageHashMapper.class), library, writer);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var hashing = workers.submit(() -> scan.rebuildArtwork(id));
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                var repair = workers.submit(() -> { update(id, true, directory); return null; });
                assertThatThrownBy(() -> repair.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                resume.countDown();
                assertThat(hashing.get(10, TimeUnit.SECONDS)).hasValue(1);
                repair.get(10, TimeUnit.SECONDS);
            } finally { resume.countDown(); }
        }
    }

    private void register(long id, Path folder) throws Exception {
        library.insertArtwork(InsertArtworkArgument.builder().artworkId(id).title("image").folder(folder.toString())
                .count(1).extensions("png").time(library.getUniqueTime()).build());
        library.replaceArtworkFileNames(id, List.of("page"));
        media.save(id, 0, new ArtworkMediaManifest("png", List.of("png")));
    }

    private Path pageDirectory(String name, boolean reversed) throws Exception {
        Path folder = Files.createDirectory(root.resolve(name));
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
            image.setRGB(x, y, (x < 16) == reversed ? 0xFFFFFF : 0);
        assertThat(ImageIO.write(image, "png", folder.resolve("page.png").toFile())).isTrue();
        return folder;
    }

    private void update(long id, boolean moved, Path folder) throws Exception {
        var response = OpenApiResponseAcceptance.post(client, admin, base, "/api/tools/directories",
                Map.of("artworkId", id, "moved", moved, "path", folder.toString()));
        assertThat(response.statusCode()).isEqualTo(204);
    }

    private List<Integer> pages(long id) {
        return db.queryForList("SELECT page FROM artwork_image_hashes WHERE artwork_id=? ORDER BY page", Integer.class, id);
    }

    private void assertHashes(long id, ImageHasher.Hashes hash) {
        assertThat(pages(id)).containsExactly(0);
        var stored = db.queryForMap("SELECT dhash,ahash FROM artwork_image_hashes WHERE artwork_id=? AND page=0", id);
        assertThat(((Number) stored.get("dhash")).longValue()).isEqualTo(hash.dHash());
        assertThat(((Number) stored.get("ahash")).longValue()).isEqualTo(hash.aHash());
    }
}
