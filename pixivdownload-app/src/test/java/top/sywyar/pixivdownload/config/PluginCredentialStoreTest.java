package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件凭证存储")
class PluginCredentialStoreTest {

    private static final String FAKE_CREDENTIAL = "fixture-credential-7f4c2a91";

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreConfigDirectory() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
    }

    @Test
    @DisplayName("按 owner 原子写入、回读验证并显式清除凭证")
    void storesAndClearsOwnerScopedCredential() throws Exception {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("config").toString());
        PluginCredentialStore store = new PluginCredentialStore();

        store.update("fixture", Map.of("fixture.api-key", FAKE_CREDENTIAL));

        assertThat(store.readAll("fixture")).containsEntry("fixture.api-key", FAKE_CREDENTIAL);
        assertThat(store.readAll("other")).isEmpty();
        Path path = RuntimeFiles.resolvePluginCredentialPath("fixture");
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).contains(FAKE_CREDENTIAL);

        store.update("fixture", Map.of("fixture.api-key", ""));

        assertThat(store.readAll("fixture")).isEmpty();
        assertThat(path).doesNotExist();
    }
}
