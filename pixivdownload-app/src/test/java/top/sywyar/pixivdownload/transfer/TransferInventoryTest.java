package top.sywyar.pixivdownload.transfer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("环境归档的插件补齐提示")
class TransferInventoryTest {
    @TempDir Path temp;

    @AfterEach
    void cleanDirectory() throws IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(temp);
    }

    @Test @DisplayName("只导出仓库地址和插件身份，不复制信任、网络放行或 URL 凭据")
    void excludesTrustAndUrlCredentials() throws Exception {
        var host = mock(DesktopUiHost.class);
        var config = mock(DesktopUiHost.ConfigFile.class);
        when(host.applicationConfig()).thenReturn(config);
        when(host.readPluginRepositories(config)).thenReturn(List.of(new RepositoryConfigEntry("fixture", "",
                "https://catalog.example.invalid/manifest.json?secret=private", true, "custom", true, false, true, true,
                1, 2, 3, 4, List.of(), Map.of("descriptor-url", "https://catalog.example.invalid/repository.json",
                "descriptor-sha256", "confirmed-trust", "unknown-secret", "private-field"))));
        var plugin = new DesktopUiPluginSnapshot("fixture", false, "fixture", 1, false, null, "", List.of(), List.of(), List.of(), List.of(), List.of());
        var source = TransferInventory.export(host, null, List.of(plugin));
        try (var input = source.content().open()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(json).contains("fixture", "https://catalog.example.invalid/repository.json")
                    .doesNotContain("private", "confirmed-trust", "proxy", "trustedKeys", "allowNonPublic");
        }
        var codec = new TransferArchive();
        Path file = temp.resolve("inventory");
        var inspected = codec.write(file, null, "fixture", List.of(source));
        try (var contents = codec.readSelected(file, null, inspected, TransferInventory.indices(inspected))) {
            var inventory = TransferInventory.read(inspected, contents);
            assertThat(inventory.repositories()).singleElement().satisfies(repository -> assertThat(repository.manifestUrl()).isEmpty());
            assertThat(TransferInventory.preview(inventory, null, List.of())).singleElement()
                    .satisfies(item -> assertThat(item.installed()).isFalse());
            assertThat(TransferInventory.preview(inventory, null, List.of(plugin))).singleElement()
                    .satisfies(item -> assertThat(item.installed()).isTrue());
        }
        verifyNoInteractions(config);
    }
}
