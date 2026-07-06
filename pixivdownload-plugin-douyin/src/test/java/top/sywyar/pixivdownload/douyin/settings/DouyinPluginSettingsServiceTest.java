package top.sywyar.pixivdownload.douyin.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinPluginSettingsService 抖音插件设置")
class DouyinPluginSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("缺省设置继承宿主下载目录与代理")
    void defaultsInheritHostSettings() {
        Path inherited = tempDir.resolve("downloads").resolve("douyin");
        DouyinPluginSettingsService service = new DouyinPluginSettingsService(
                tempDir.resolve("config").resolve("douyin.properties"),
                inherited);

        DouyinPluginSettings settings = service.load();

        assertThat(settings.downloadDirectory()).isEmpty();
        assertThat(settings.proxyMode()).isEqualTo(DouyinProxyMode.INHERIT);
        assertThat(settings.proxyHost()).isEmpty();
        assertThat(settings.proxyPort()).isEmpty();
        assertThat(service.runtimeSettings().downloadDirectory()).isEqualTo(inherited.normalize());
        assertThat(service.runtimeSettings().proxyMode()).isEqualTo(DouyinProxyMode.INHERIT);
        assertThat(service.runtimeSettings().proxyHost()).isEmpty();
        assertThat(service.runtimeSettings().proxyPort()).isZero();
    }

    @Test
    @DisplayName("从插件 properties 配置字段读取运行设置")
    void loadsSettingsFromPluginPropertiesFields() throws Exception {
        Path configFile = tempDir.resolve("config").resolve("plugins").resolve("douyin.properties");
        Files.createDirectories(configFile.getParent());
        Path customDirectory = tempDir.resolve("custom-douyin");
        String customDirectoryValue = customDirectory.toString().replace('\\', '/');
        Files.writeString(configFile, String.join("\n",
                "douyin.download.directory=" + customDirectoryValue,
                "douyin.proxy.mode=custom",
                "douyin.proxy.host=127.0.0.1",
                "douyin.proxy.port=10809",
                ""));
        DouyinPluginSettingsService service = new DouyinPluginSettingsService(
                configFile,
                tempDir.resolve("downloads").resolve("douyin"));

        DouyinPluginSettings settings = service.load();

        assertThat(settings.downloadDirectory()).isEqualTo(customDirectoryValue);
        assertThat(settings.proxyMode()).isEqualTo(DouyinProxyMode.CUSTOM);
        assertThat(settings.proxyHost()).isEqualTo("127.0.0.1");
        assertThat(settings.proxyPort()).isEqualTo("10809");
        assertThat(service.runtimeSettings().downloadDirectory()).isEqualTo(customDirectory.normalize());
        assertThat(service.runtimeSettings().proxyHost()).isEqualTo("127.0.0.1");
        assertThat(service.runtimeSettings().proxyPort()).isEqualTo(10809);
    }

    @Test
    @DisplayName("无效自定义代理端口不会进入运行设置")
    void ignoresInvalidCustomProxyPortInRuntimeSettings() throws Exception {
        Path configFile = tempDir.resolve("config").resolve("plugins").resolve("douyin.properties");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, String.join("\n",
                "douyin.proxy.mode=custom",
                "douyin.proxy.host=127.0.0.1",
                "douyin.proxy.port=invalid",
                ""));
        DouyinPluginSettingsService service = new DouyinPluginSettingsService(
                configFile,
                tempDir.resolve("downloads").resolve("douyin"));

        assertThat(service.load().proxyMode()).isEqualTo(DouyinProxyMode.CUSTOM);
        assertThat(service.runtimeSettings().proxyPort()).isZero();
        assertThat(service.runtimeSettings().hasCustomProxyEndpoint()).isFalse();
    }
}
