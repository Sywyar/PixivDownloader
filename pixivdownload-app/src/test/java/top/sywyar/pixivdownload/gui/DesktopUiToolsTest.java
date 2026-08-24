package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopUiToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void checksAndUpdatesArtworkFoldersThroughTheHostEngine() throws Exception {
        Path database = tempDir.resolve("artworks.db");
        Path existing = Files.createDirectory(tempDir.resolve("existing"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT, folder TEXT,"
                    + " moved INTEGER, move_folder TEXT, deleted INTEGER, time INTEGER)");
            statement.execute("INSERT INTO artworks VALUES (1, 'ok', '" + sql(existing) + "', 0, NULL, 0, 2)");
            statement.execute("INSERT INTO artworks VALUES (2, 'missing', '" + sql(tempDir.resolve("missing"))
                    + "', 0, NULL, 0, 1)");
            statement.execute("INSERT INTO artworks VALUES (3, 'moved without destination', '" + sql(existing)
                    + "', 1, NULL, 0, 0)");
        }

        DesktopUiTools tools = new DesktopUiTools();
        DesktopUiToolHost.FolderCheckResult before = tools.checkArtworkFolders(database);
        assertThat(before.total()).isEqualTo(3);
        assertThat(before.inaccessible()).extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(2L);

        tools.updateArtworkFolder(database, 2, false, existing.toString());
        assertThat(tools.checkArtworkFolders(database).inaccessible()).isEmpty();
    }

    @Test
    void classifiesImagesAndMovesTheWorkSidecar() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("123"));
        Path image = Files.writeString(source.resolve("image.jpg"), "image");
        Path sidecar = Files.writeString(source.resolve("123.meta.json"), "{}");
        Path target = tempDir.resolve("target");
        DesktopUiTools tools = new DesktopUiTools();

        Path destination = tools.classifyImageFolder(
                source, List.of(image), 123L, target,
                new DesktopUiToolHost.ImageClassifierServer(false, "http://localhost:6999"),
                (detail, folder) -> {
                    throw new AssertionError("Deletion should not fail: " + detail);
                });

        assertThat(destination).isEqualTo(target);
        assertThat(target.resolve(image.getFileName())).exists();
        assertThat(target.resolve(sidecar.getFileName())).exists();
        assertThat(source).doesNotExist();
    }

    private static String sql(Path path) {
        return path.toString().replace("'", "''");
    }
}
