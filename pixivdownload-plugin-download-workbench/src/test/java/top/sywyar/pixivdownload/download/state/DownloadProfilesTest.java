package top.sywyar.pixivdownload.download.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("下载方案独立文件与并发保存")
class DownloadProfilesTest {
    @TempDir Path directory;

    private DownloadProfiles store() {
        RuntimePathProvider paths = mock(RuntimePathProvider.class);
        when(paths.configFile("json")).thenReturn(directory.resolve("download-workbench.json"));
        return new DownloadProfiles(paths);
    }

    private DownloadProfiles.Profile profile(String name) {
        var json = new JsonMapper();
        return new DownloadProfiles.Profile("profile-a", name,
                json.createObjectNode().put("concurrent", 3).put("imageFormats", "webp").put("autoStartOnEnqueue", true),
                json.createObjectNode().put("content", "safe"));
    }

    @Test @DisplayName("只读不创建文件且重启后恢复方案")
    void persistsIndependentDocument() throws Exception {
        var store = store();
        assertThat(store.read()).isEqualTo(new DownloadProfiles.Snapshot("0", List.of()));
        assertThat(directory.resolve("download-workbench.json")).doesNotExist();
        var request = new JsonMapper().writeValueAsBytes(new DownloadProfiles.Snapshot("0", List.of(profile("日常"))));
        var saved = store.save(DownloadProfiles.parse(request));
        assertThat(store().read()).isEqualTo(saved);
        assertThat(Files.readString(directory.resolve("download-workbench.json"), StandardCharsets.UTF_8))
                .contains("schemaVersion").doesNotContain("\"queue\"", "\"cookie\"", "\"revision\"");
    }

    @Test @DisplayName("旧标签页覆盖和重复名称被拒绝并保留现存方案")
    void rejectsStaleRevision() throws Exception {
        var store = store();
        var saved = store.save(new DownloadProfiles.Snapshot("0", List.of(profile("A"))));
        assertThatThrownBy(() -> store().save(new DownloadProfiles.Snapshot("0", List.of(profile("B")))))
                .isInstanceOf(DownloadProfiles.Conflict.class);
        assertThatThrownBy(() -> store.save(new DownloadProfiles.Snapshot(saved.revision(),
                List.of(profile("A"), profile("A"))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.read()).isEqualTo(saved);
    }

    @Test @DisplayName("损坏文件不回退为空也不被覆盖")
    void preservesCorruptFile() throws Exception {
        var file = directory.resolve("download-workbench.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store().read()).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> store().save(new DownloadProfiles.Snapshot("0", List.of(profile("A")))))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{broken");
    }

    @Test @DisplayName("凭据、目录、队列、深层对象和超大输入不能写入方案")
    void rejectsOutOfScopeData() {
        for (String key : List.of("cookie", "accessToken", "credentialIds", "collectionId", "rootFolder", "queue")) {
            var profile = profile("A");
            profile.settings().put(key, "private");
            assertThatThrownBy(() -> store().save(new DownloadProfiles.Snapshot("0", List.of(profile))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var nested = profile("A");
        nested.settings().putObject("nested").put("key", "value");
        assertThatThrownBy(() -> store().save(new DownloadProfiles.Snapshot("0", List.of(nested))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DownloadProfiles.parse(new byte[DownloadProfiles.MAX_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
