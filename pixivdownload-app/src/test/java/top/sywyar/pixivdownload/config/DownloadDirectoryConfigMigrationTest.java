package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DownloadDirectoryConfigMigrationTest {
    @TempDir Path temp;

    @Test
    @DisplayName("旧相对根和非扁平 User 布局迁移后保留，重复执行不覆盖主动清空")
    void migratesRelativeRootAndPreservesClearedOverride() throws Exception {
        Path file = temp.resolve("config.yaml");
        Files.writeString(file, "# retained\ndownload.root-folder: custom-pictures\ndownload.user-flat-folder: false\nserver.port: 8765\n", StandardCharsets.UTF_8);
        DownloadDirectoryConfigMigration.migrate(file);
        var editor = new ConfigFileEditor(file);
        assertEquals("custom-pictures/{artwork_id}", editor.read("download.root-folder"));
        assertEquals("custom-pictures/{username}/{R18}/{artwork_id}", editor.read("download.mode-templates.user"));
        assertNull(editor.read("download.user-flat-folder"));
        editor.write("download.mode-templates.user", "");
        byte[] before = Files.readAllBytes(file);
        DownloadDirectoryConfigMigration.migrate(file);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(Files.readString(file).contains("# retained"));
        assertEquals("8765", editor.read("server.port"));
    }

    @Test
    @DisplayName("旧绝对根与 flat=true 迁移为默认模板和空 User 覆盖")
    void migratesAbsoluteFlatRoot() throws Exception {
        Path file = temp.resolve("config.yaml");
        String root = temp.resolve("pictures").toString().replace('\\', '/');
        Files.writeString(file, "download.root-folder: '" + root + "'\ndownload.user-flat-folder: true\n", StandardCharsets.UTF_8);
        DownloadDirectoryConfigMigration.migrate(file);
        var editor = new ConfigFileEditor(file);
        assertEquals(root + "/{artwork_id}", editor.read("download.root-folder"));
        assertEquals("", editor.read("download.mode-templates.user"));
        assertEquals("", editor.read("download.mode-templates.series"));
        var environment = new org.springframework.core.env.StandardEnvironment();
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load("fixture",
                new org.springframework.core.io.FileSystemResource(file))) environment.getPropertySources().addFirst(source);
        DownloadConfig bound = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("download", org.springframework.boot.context.properties.bind.Bindable.of(DownloadConfig.class)).get();
        bound.validateTemplates();
        assertEquals(root + "/{artwork_id}", bound.directoryTemplate("user"));
        assertFalse(Files.exists(temp.resolve("pictures")));
    }

    @Test
    @DisplayName("新安装默认是相对最终目录，独立模式覆盖留空才回退")
    void selectsOnlyExplicitModeAndRejectsBrokenOverride() {
        var config = new DownloadConfig();
        assertFalse(Path.of(config.getRootFolder()).isAbsolute());
        assertEquals("pixiv-download/{artwork_id}", config.directoryTemplate(null));
        config.setModeTemplates(Map.of("user", "", "series", "series/{artwork_id}"));
        assertEquals(config.directoryTemplate(null), config.directoryTemplate("user"));
        assertEquals("series/{artwork_id}", config.directoryTemplate("series"));
        config.setModeTemplates(Map.of("series", "broken"));
        assertThrows(IllegalArgumentException.class, () -> config.directoryTemplate("series"));
    }
}
