package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.management.PluginEnabledConfigurationService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "plugins.download-workbench.enabled=false")
@DisplayName("下载工作台已安装但停用时，宿主与作品入口仍可使用")
class DownloadWorkbenchDisabledBootContextTest extends DownloadWorkbenchOptionalBootContextTest {
    @Autowired private PluginEnabledConfigurationService enabledConfiguration;

    @Test
    @DisplayName("允许保存下载工作台的启用与停用设置")
    void workbenchCanBeConfigured() throws IOException {
        assertThat(enabledConfiguration.update("download-workbench", true).enabled()).isTrue();
        assertThat(enabledConfiguration.update("download-workbench", false).enabled()).isFalse();
        assertThat(Files.readString(RuntimeFiles.resolveConfigYamlPath(), StandardCharsets.UTF_8))
                .contains("plugins.download-workbench.enabled: false");
    }
}
