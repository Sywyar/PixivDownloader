package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaStoreImpl;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ArtworkMediaStoreTest {
    @TempDir Path directory;

    private SQLiteDataSource dataSource() {
        var config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        var source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + directory.resolve("media.db"));
        return source;
    }

    @Test
    @DisplayName("逐页并发保存不覆盖其它页，重开数据库仍保留原图身份和输出格式")
    void concurrentPagesSurviveReopen() throws Exception {
        var source = dataSource();
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var initializer = new DatabaseInitializer(new JdbcTemplate(source), registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        var store = new ArtworkMediaStoreImpl(source, initializer);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int page = 0; page < 24; page++) {
                int index = page;
                tasks.add(workers.submit(() -> {
                    store.save(42L, index, new ArtworkMediaManifest(index % 2 == 0 ? "jpg" : "png", List.of("webp"), false));
                    return null;
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        var reopened = new ArtworkMediaStoreImpl(dataSource(), initializer);
        for (int page = 0; page < 24; page++) {
            var saved = reopened.find(42L, page).orElseThrow();
            assertEquals(page % 2 == 0 ? "jpg" : "png", saved.originalExtension());
            assertEquals(List.of("webp"), saved.extensions());
            assertFalse(saved.originalRetained());
        }
        reopened.save(42L, 0, new ArtworkMediaManifest("jpg", List.of("webp", "jpg"), false));
        assertFalse(reopened.find(42L, 0).orElseThrow().originalRetained());
        assertTrue(reopened.find(99L, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reopened.save(0L, 0, new ArtworkMediaManifest("jpg", List.of("jpg"))));
        new JdbcTemplate(source).execute("DROP TABLE artwork_media");
        assertThrows(java.io.IOException.class, () -> reopened.save(42L, 0, new ArtworkMediaManifest("jpg", List.of("jpg"))));
    }
}
