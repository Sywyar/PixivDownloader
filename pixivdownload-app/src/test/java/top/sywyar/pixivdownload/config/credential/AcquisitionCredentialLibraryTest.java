package top.sywyar.pixivdownload.config.credential;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("独立采集凭据库")
class AcquisitionCredentialLibraryTest {
    @TempDir Path directory;
    private String previous;
    private AcquisitionCredentialLibrary library;
    private PluginCredentialStore store;
    @BeforeEach void setup() {
        previous = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, directory.toString());
        store = new PluginCredentialStore(PluginCredentialKeyMaterial.forTesting(new byte[32], new byte[32]));
        library = new AcquisitionCredentialLibrary(store);
    }
    @AfterEach void restore() {
        if (previous == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previous);
    }
    @Test @DisplayName("加密持久化并隐藏秘密，读取不产生空库")
    void encrypted() throws Exception {
        var empty = library.read();
        assertThat(Files.exists(RuntimeFiles.peekPluginCredentialPath("core"))).isFalse();
        var saved = library.save(new AcquisitionCredentialLibrary.Update(empty.revision(), List.of(
                new AcquisitionCredentialLibrary.Entry("a", "首选", true, "PHPSESSID=1_fixture"))));
        assertThat(Files.readString(RuntimeFiles.peekPluginCredentialPath("core"))).doesNotContain("PHPSESSID", "首选");
        assertThat(JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(saved)).doesNotContain("PHPSESSID", "secret");
        assertThat(new AcquisitionCredentialLibrary(store).resolve(List.of("a")).primary()).isEqualTo("PHPSESSID=1_fixture");
    }
    @Test @DisplayName("库编辑不改变已取得的副本，过期写入和无效选择明确拒绝")
    void independentCopies() throws Exception {
        var first = library.save(new AcquisitionCredentialLibrary.Update(library.read().revision(), List.of(
                new AcquisitionCredentialLibrary.Entry("a", "A", true, "PHPSESSID=1_old"),
                new AcquisitionCredentialLibrary.Entry("b", "B", true, "PHPSESSID=2_old"))));
        var captured = library.resolve(List.of("b", "a"));
        var second = library.save(new AcquisitionCredentialLibrary.Update(first.revision(), List.of(
                new AcquisitionCredentialLibrary.Entry("a", "renamed", false, null))));
        assertThat(captured.primary()).isEqualTo("PHPSESSID=2_old");
        assertThatThrownBy(() -> library.resolve(List.of("a"))).isInstanceOf(top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException.class);
        assertThatThrownBy(() -> library.resolve(List.of("b"))).isInstanceOf(top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException.class);
        assertThatThrownBy(() -> library.save(new AcquisitionCredentialLibrary.Update(first.revision(), List.of())))
                .isInstanceOf(AcquisitionCredentialLibrary.Conflict.class);
        assertThat(library.read()).isEqualTo(second);
    }
}
