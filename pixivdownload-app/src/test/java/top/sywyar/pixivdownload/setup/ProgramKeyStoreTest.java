package top.sywyar.pixivdownload.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("程序密钥持久化与撤销")
class ProgramKeyStoreTest {
    @TempDir Path directory;
    private ProgramKeyStore.Policy restricted() {
        return new ProgramKeyStore.Policy("worker", Set.of("example:items.read"), false,
                List.of("192.0.2.0/24", "2001:db8::/64"));
    }

    @Test
    @DisplayName("只落盘摘要并在重启后保持权限与来源限制")
    void persistsDigestAndEnforcesNetworks() throws Exception {
        Path file = directory.resolve("keys.json");
        var created = new ProgramKeyStore(file).create(restricted());
        assertThat(Files.readString(file)).doesNotContain(created.secret());
        assertThat(created.toString()).doesNotContain(created.secret());
        var reloaded = new ProgramKeyStore(file);
        assertThat(reloaded.authenticate(created.secret(), "192.0.2.255").policy().permissions())
                .containsExactly("example:items.read");
        assertThat(reloaded.authenticate(created.secret(), "2001:db8::1234")).isNotNull();
        assertThat(reloaded.authenticate(created.secret(), "198.51.100.1")).isNull();
        assertThat(reloaded.authenticate(created.secret(), "localhost")).isNull();
        assertThat(reloaded.authenticate(created.secret() + "x", "192.0.2.1")).isNull();
        assertThat(reloaded.list().getFirst().lastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("编辑与撤销核对修订并立即影响相同密钥")
    void narrowsAndRevokesWithoutChangingSecret() throws Exception {
        Path file = directory.resolve("keys.json");
        var store = new ProgramKeyStore(file);
        var created = store.create(restricted());
        var empty = new ProgramKeyStore.Policy("renamed", Set.of(), true, List.of());
        store.update(created.key().id(), 1, empty);
        assertThatIllegalStateException().isThrownBy(() -> store.update(created.key().id(), 1, restricted()));
        assertThat(store.authenticate(created.secret(), "198.51.100.1").policy().permissions()).isEmpty();
        store.revoke(created.key().id(), 2);
        assertThat(store.authenticate(created.secret(), "192.0.2.1")).isNull();
        assertThat(new ProgramKeyStore(file).authenticate(created.secret(), "192.0.2.1")).isNull();
    }

    @Test
    @DisplayName("保存失败保留旧授权且损坏文件不重建为空状态")
    void failsClosedAndRollsBack() throws Exception {
        Path file = directory.resolve("keys.json");
        var store = new ProgramKeyStore(file);
        var created = store.create(restricted());
        byte[] previous = Files.readAllBytes(file);
        Files.delete(file); Files.createDirectory(file);
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> store.revoke(created.key().id(), 1));
        assertThat(store.authenticate(created.secret(), "192.0.2.1")).isNotNull();
        Files.delete(file); Files.writeString(file, "{broken");
        var damaged = new ProgramKeyStore(file);
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(damaged::list);
        assertThat(Files.readString(file)).isEqualTo("{broken");
        Files.write(file, previous);
        assertThat(damaged.authenticate(created.secret(), "192.0.2.1")).isNotNull();
    }

    @Test
    @DisplayName("空限制列表与隐式通配权限不能扩大授权")
    void rejectsAmbiguousPolicy() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramKeyStore.Policy("worker", Set.of(), false, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramKeyStore.Policy("worker", Set.of("example:*"), true, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramKeyStore.Policy("worker", Set.of(), true, List.of("127.0.0.1")));
    }
}
