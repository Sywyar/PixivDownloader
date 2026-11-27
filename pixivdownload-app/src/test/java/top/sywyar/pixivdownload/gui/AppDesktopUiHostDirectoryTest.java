package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@DisplayName("两版桌面共用的目录模板校验与根迁移")
class AppDesktopUiHostDirectoryTest {
    @TempDir Path temp;

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void savesTheSelectedDirectoryAndKeepsTheTemplateSuffix() throws Exception {
        Path nested = Files.createDirectories(temp.resolve("real/nested"));
        Files.createSymbolicLink(temp.resolve("link"), nested);
        Files.createDirectory(temp.resolve("art"));
        Files.createDirectory(temp.resolve("images"));
        var editor = new ConfigFileEditor(temp.resolve("config.yaml"));
        editor.write("download.root-folder", "old/{author_name}/{artwork_id}");
        var host = new AppDesktopUiHost(0, editor.asConfigFile());
        for (String selected : java.util.List.of("link/../art", "images ", " images")) {
            Path directory = Files.createDirectory(temp.resolve(selected));
            Path artwork = Files.writeString(directory.resolve("work"), "art");
            String relative = top.sywyar.pixivdownload.core.work.directory.WorkDirectoryTemplate
                    .relativeToAppRoot(directory.toString(), temp.toString());
            for (String choice : java.util.List.of(relative, directory.toString())) {
                host.saveDownloadTemplateRoot(choice);
                String saved = editor.read("download.root-folder");
                assertThat(saved).endsWith("/{author_name}/{artwork_id}");
                Path restored = temp.resolve(host.normalizeRootFolder(saved));
                assertThat(Files.isSameFile(restored, directory)).isTrue();
                assertThat(Files.isSameFile(restored.resolve("work"), artwork)).isTrue();
                var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
                config.setRootFolder(saved);
                assertThat(Files.isSameFile(temp.resolve(config.getRootFolder()), directory)).isTrue();
            }
        }
    }

    @Test @DisplayName("工具页保存预设只修改预设键，校验失败保留原值")
    void savesOnlyImportPresets() throws Exception {
        var editor = new ConfigFileEditor(temp.resolve("config.yaml"));
        editor.writeAll(Map.of("server.port", "8765", "local-import.presets", "[]"));
        var host = new AppDesktopUiHost(0, editor.asConfigFile());
        String value = host.formatLocalImportPresets(java.util.List.of(
                new top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset(
                        temp.resolve("{artwork_id}") + "/")));
        host.saveLocalImportPresets(value);
        assertThat(editor.read("local-import.presets")).isEqualTo(value);
        assertThat(editor.read("server.port")).isEqualTo("8765");
        assertThatThrownBy(() -> host.saveLocalImportPresets("invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThat(editor.read("local-import.presets")).isEqualTo(value);
        host.saveLocalImportPresets("[]");
        assertThat(editor.read("local-import.presets")).isEqualTo("[]");
    }

    @Test void rejectsUnrepresentableDirectoryBeforeSaving() throws Exception {
        var editor = new ConfigFileEditor(temp.resolve("config.yaml"));
        String original = "old/{author_name}/{artwork_id}/pages";
        editor.write("download.root-folder", original);
        var host = new AppDesktopUiHost(0, editor.asConfigFile());
        var choices = new java.util.ArrayList<>(java.util.List.of("images{artwork_id}"));
        if (java.io.File.separatorChar == '/') {
            Files.createDirectories(temp.resolve("group/art"));
            choices.add("group\\art");
        }
        for (String name : choices) {
            String root = Files.createDirectory(temp.resolve(name)).toString();
            String reason = host.message("directory-template.literal-root");
            assertThatIOException().isThrownBy(() -> host.validateDownloadTemplateRoot(root)).withMessage(reason);
            assertThatIOException().isThrownBy(() -> host.saveDownloadTemplateRoot(root)).withMessage(reason);
            assertThat(editor.read("download.root-folder")).isEqualTo(original);
        }
    }

    @Test @DisplayName("共享入口拒绝非独占模板，固定前缀迁移保留完整后缀和其它模式")
    void validatesAndMigratesPrefixOnly() throws Exception {
        var editor = new ConfigFileEditor(temp.resolve("config.yaml"));
        editor.writeAll(Map.of("download.root-folder", "pictures/{author_name}/{artwork_id}/pages",
                "download.mode-templates.user", "users/{username}/{artwork_id}", "server.port", "8765"));
        var host = new AppDesktopUiHost(0, editor.asConfigFile());
        assertThatCode(() -> host.validateDirectoryTemplate("books/{artwork_id}/text")).doesNotThrowAnyException();
        assertThatCode(() -> host.validateCoreConfigValue("download.mode-templates.series", "")).doesNotThrowAnyException();
        assertThatThrownBy(() -> host.validateCoreConfigValue("download.root-folder", ""))
                .hasCauseInstanceOf(DirectoryTemplateException.class);
        assertThatThrownBy(() -> host.validateDirectoryTemplate("books/{author_id}{artwork_id}"))
                .hasCauseInstanceOf(DirectoryTemplateException.class);
        String next = host.saveDownloadTemplateRoot(temp.resolve("new-root").toString());
        assertThat(next.replace('\\', '/')).endsWith("/new-root/{author_name}/{artwork_id}/pages");
        assertThat(Path.of(host.normalizeRootFolder(next))).isEqualTo(temp.resolve("new-root"));
        assertThat(editor.read("download.mode-templates.user")).isEqualTo("users/{username}/{artwork_id}");
        assertThat(editor.read("server.port")).isEqualTo("8765");
        assertThat(temp.resolve("new-root")).doesNotExist();
    }
}
