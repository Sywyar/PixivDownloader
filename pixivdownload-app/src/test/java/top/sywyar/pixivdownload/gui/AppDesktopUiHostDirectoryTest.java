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

    @Test @DisplayName("共享入口拒绝非独占模板，固定前缀迁移保留完整后缀和其它模式")
    void validatesAndMigratesPrefixOnly() throws Exception {
        var editor = new ConfigFileEditor(temp.resolve("config.yaml"));
        editor.writeAll(Map.of("download.root-folder", "pictures/{authorName}/{artworkId}/pages",
                "download.mode-templates.user", "users/{username}/{artworkId}", "server.port", "8765"));
        var host = new AppDesktopUiHost(0, editor.asConfigFile());
        assertThatCode(() -> host.validateDirectoryTemplate("books/{artwork_id}/text")).doesNotThrowAnyException();
        assertThatCode(() -> host.validateCoreConfigValue("download.mode-templates.series", "")).doesNotThrowAnyException();
        assertThatThrownBy(() -> host.validateCoreConfigValue("download.root-folder", ""))
                .hasCauseInstanceOf(DirectoryTemplateException.class);
        assertThatThrownBy(() -> host.validateDirectoryTemplate("books/{authorId}{artworkId}"))
                .hasCauseInstanceOf(DirectoryTemplateException.class);
        String next = host.saveDownloadTemplateRoot(temp.resolve("new-root").toString());
        assertThat(next.replace('\\', '/')).endsWith("/new-root/{authorName}/{artworkId}/pages");
        assertThat(Path.of(host.normalizeRootFolder(next))).isEqualTo(temp.resolve("new-root"));
        assertThat(editor.read("download.mode-templates.user")).isEqualTo("users/{username}/{artworkId}");
        assertThat(editor.read("server.port")).isEqualTo("8765");
        assertThat(temp.resolve("new-root")).doesNotExist();
    }
}
