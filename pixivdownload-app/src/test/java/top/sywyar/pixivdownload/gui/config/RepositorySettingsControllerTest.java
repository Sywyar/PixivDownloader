package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.repository.*;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("仓库设置 API 的配置与信任边界")
class RepositorySettingsControllerTest {
    @TempDir Path root;

    @Test
    @DisplayName("真实保存保留未知字段和无关配置，旧修订不能覆盖后来的修改")
    void preservesUnknownSettingsAndRejectsStaleWrites() throws Exception {
        Path config = root.resolve("config.yaml");
        Files.writeString(config, "# keep\nunrelated: true\nplugin-catalog.repositories:\n"
                + "  - id: sample.repo\n    manifest-url: https://example.test/catalog.json\n    future-setting: retained\n");
        var clients = mock(PluginCatalogClientProvider.class);
        var imports = new PluginRepositoryImportService(new PluginRepositoryRegistry(new PluginCatalogProperties()),
                clients, new PluginCatalogTrustStateStore(root.resolve("trust.json")));
        var messages = TestI18nBeans.appMessages();
        var controller = new RepositorySettingsController(new ConfigFileEditor(config).asConfigFile(), imports, messages,
                new ToolApiRequests(messages, mock(WebI18nService.class)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var snapshot = controller.read();
        assertThat(snapshot.entries().getFirst().extraFields()).isEmpty();
        var entry = RepositoryConfigEntry.create("sample.repo", "", "https://example.test/catalog.json", false,
                "direct-strict", 0, 0, 0, 0);
        var body = JsonMapper.builder().build().writeValueAsString(new RepositorySettingsController.Save(
                snapshot.revision(), List.of(entry), false));
        mvc.perform(put("/api/settings/repositories").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.restartRequired").value(true));
        assertThat(Files.readString(config)).contains("# keep", "unrelated: true", "future-setting: retained", "enabled: false");
        String saved = Files.readString(config);
        mvc.perform(put("/api/settings/repositories").contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_CHANGED"));
        assertThat(Files.readString(config)).isEqualTo(saved);
        verifyNoInteractions(clients);
    }

    @Test
    @DisplayName("新增信任必须明确确认，保留仓库标识在持久化前拒绝")
    void requiresTrustAndValidatesBeforeSaving() throws Exception {
        Path config = root.resolve("config.yaml");
        Files.writeString(config, "unrelated: true\n");
        var clients = mock(PluginCatalogClientProvider.class);
        var imports = new PluginRepositoryImportService(new PluginRepositoryRegistry(new PluginCatalogProperties()),
                clients, new PluginCatalogTrustStateStore(root.resolve("trust.json")));
        var messages = TestI18nBeans.appMessages();
        var controller = new RepositorySettingsController(new ConfigFileEditor(config).asConfigFile(), imports, messages,
                new ToolApiRequests(messages, mock(WebI18nService.class)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String revision = controller.read().revision();
        for (String id : List.of("sample.repo", PluginRepository.OFFICIAL_ID)) {
            var entry = RepositoryConfigEntry.create(id, "", "https://example.test/catalog.json", true,
                    "direct-strict", 0, 0, 0, 0);
            var body = JsonMapper.builder().build().writeValueAsString(new RepositorySettingsController.Save(revision, List.of(entry), false));
            mvc.perform(put("/api/settings/repositories").contentType("application/json").content(body))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.code").value(id.equals("sample.repo")
                            ? "REPOSITORY_TRUST_CONFIRMATION_REQUIRED" : "INVALID_REPOSITORY_FIELD"));
        }
        assertThat(Files.readString(config)).isEqualTo("unrelated: true\n");
        verifyNoInteractions(clients);
    }
}
