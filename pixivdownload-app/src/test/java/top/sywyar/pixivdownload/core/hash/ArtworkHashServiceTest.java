package top.sywyar.pixivdownload.core.hash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaStoreImpl;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.PixivMapper;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArtworkHashServiceTest {
    @TempDir Path directory;

    @Test void rebuildingACompleteButPollutedIndexReadsOnlyRegisteredMedia() throws Exception {
        Path source = directory.resolve("page.webp");
        try (var fixture = getClass().getResourceAsStream("/images/gradient-animated.webp")) {
            Files.copy(fixture, source);
        }
        Path userFile = directory.resolve("page_thumb.jpg");
        assertThat(ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpeg", userFile.toFile())).isTrue();
        byte[] userBytes = Files.readAllBytes(userFile);
        var correct = ImageHasher.hash(source).orElseThrow();
        var wrong = ImageHasher.hash(userFile).orElseThrow();
        assertThat(correct).isNotEqualTo(wrong);

        try (var db = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var main = DatabaseProviderTestFixtures.main(db);
            var messages = TestI18nBeans.appMessages();
            var schema = DatabaseSchemaRegistry.forBuiltInPlugins();
            var initializer = DatabaseProviderTestFixtures.initializer(new JdbcTemplate(db), schema.contributions(),
                    schema.mergedSchema(), messages, event -> {});
            initializer.initialize();
            var config = new DownloadConfig();
            config.setRootFolder(directory.resolve("{artwork_id}").toString());
            var paths = new PathPrefixCodec(new PathPrefixMapper(main), config, messages);
            paths.init();
            var library = new PixivDatabase(new PixivMapper(main), messages, paths, initializer);
            library.insertArtwork(InsertArtworkArgument.builder().artworkId(42L).title("animation").folder(directory.toString())
                    .count(1).extensions("webp").time(10L).build());
            library.replaceArtworkFileNames(42, List.of("page"));
            var media = new ArtworkMediaStoreImpl(main, initializer);
            media.save(42, 0, new ArtworkMediaManifest("zip", List.of("webp"), false, false));

            var locator = new ArtworkFileLocator(library, config, messages, mock(StagedFileDeletion.class), media,
                    new ExternalWorkFiles(main, paths, initializer), new DownloadDirectories(main, paths, initializer));
            var rows = new ImageHashMapper(main);
            var decoder = new ArtworkMediaDecoder(mock(FfmpegRunner.class), JsonMapper.shared());
            var writer = new ArtworkHashService(rows, locator, messages, new DataSourceTransactionManager(db), decoder, paths);
            var index = new ArtworkHashIndexAdapter(rows, library, writer);
            rows.upsert(42, 0, "webp", wrong.dHash(), wrong.aHash(), 10);
            assertThat(index.artworkIdsMissingHashes(10)).isEmpty();

            assertThat(index.rebuildArtwork(42)).hasValue(1);
            assertThat(rows.findAll()).singleElement().satisfies(row -> {
                assertThat(row.ext()).isEqualTo("webp");
                assertThat(row.dHash()).isEqualTo(correct.dHash());
                assertThat(row.aHash()).isEqualTo(correct.aHash());
                assertThat(row.createdTime()).isGreaterThan(10);
            });
            Files.move(source, directory.resolve("saved.webp"));
            assertThat(index.rebuildArtwork(42)).hasValue(0);
            assertThat(index.findAllEntries()).isEmpty();
            assertThat(index.artworkIdsMissingHashes(10)).isEmpty();
            assertThat(Files.readAllBytes(userFile)).isEqualTo(userBytes);
        }
    }
}
