package top.sywyar.pixivdownload.novel.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("小说独立目录模板初始化")
class NovelDirectorySettingsTest {
    @TempDir Path temp;

    @Test @DisplayName("保留宿主旧根和空 User 覆盖，重复启动不覆盖小说自有配置")
    void preservesRootAndExplicitEmptyOverride() throws Exception {
        var host = mock(DownloadSettings.class);
        when(host.directoryTemplate(null)).thenReturn(temp + "/pictures/{artworkId}");
        when(host.directoryOverrides()).thenReturn(Map.of("user", "", "series", "serial/{artworkId}"));
        var paths = mock(RuntimePathProvider.class);
        Path file = temp.resolve("config/novel.properties");
        when(paths.configFile("properties")).thenReturn(file);
        var settings = NovelDirectorySettings.initialize(host, paths, new MockEnvironment());
        assertThat(settings.directoryTemplate(null)).isEqualTo(temp + "/pictures/novel-{artworkId}");
        assertThat(settings.directoryTemplate("user")).isEqualTo(settings.directoryTemplate(null));
        assertThat(settings.directoryTemplate("series")).isEqualTo("serial/novel-{artworkId}");
        assertThat(Files.exists(temp.resolve("pictures"))).isFalse();
        byte[] original = Files.readAllBytes(file);
        when(host.directoryTemplate(null)).thenReturn("changed/{artworkId}");
        NovelDirectorySettings.initialize(host, paths, new MockEnvironment());
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        var overridden = NovelDirectorySettings.initialize(host, paths, new MockEnvironment()
                .withProperty(NovelDirectorySettings.ROOT_KEY, "books/{artworkId}/text")
                .withProperty(NovelDirectorySettings.MODE_PREFIX + "series", ""));
        assertThat(overridden.directoryTemplate("series")).isEqualTo("books/{artworkId}/text");
    }

    @Test @DisplayName("保留非扁平 User 的来源用户名与分级段，错误覆盖不回退")
    void preservesLegacyUserLayout() throws Exception {
        var host = mock(DownloadSettings.class);
        when(host.directoryTemplate(null)).thenReturn("relative/{artworkId}");
        when(host.directoryOverrides()).thenReturn(Map.of("user", "relative/{username}/{R18}/{artworkId}"));
        var paths = mock(RuntimePathProvider.class);
        when(paths.configFile("properties")).thenReturn(temp.resolve("novel.properties"));
        var settings = NovelDirectorySettings.initialize(host, paths, new MockEnvironment());
        assertThat(settings.directoryTemplate("user")).isEqualTo("relative/{username}/{R18}/novel-{artworkId}");
        assertThatThrownBy(() -> new NovelDirectorySettings("books/{artworkId}", Map.of("series", "broken")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
