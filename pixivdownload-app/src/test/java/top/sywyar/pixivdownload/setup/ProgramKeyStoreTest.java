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
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var oldFormat = mapper.readTree(Files.readAllBytes(file));
        ((tools.jackson.databind.node.ObjectNode) oldFormat.path("keys").get(0)).remove("transport");
        Files.write(file, mapper.writeValueAsBytes(oldFormat));
        var reloaded = new ProgramKeyStore(file);
        assertThatExceptionOfType(ProgramKeyStore.HttpsRequired.class)
                .isThrownBy(() -> reloaded.authenticate(created.secret(), "192.0.2.4", false));
        assertThat(reloaded.authenticate(created.secret(), "192.0.2.255", true).policy().permissions())
                .containsExactly("example:items.read");
        assertThat(reloaded.authenticate(created.secret(), "2001:db8::1234", true)).isNotNull();
        assertThat(reloaded.authenticate(created.secret(), "198.51.100.1", true)).isNull();
        assertThat(reloaded.authenticate(created.secret(), "localhost", true)).isNull();
        assertThat(reloaded.authenticate(created.secret() + "x", "192.0.2.1", true)).isNull();
        assertThat(reloaded.list().getFirst().lastUsedAt()).isNotNull();
    }

    @Test
    void publicHttpNeedsTwoSeparateRevisionsAndAnEditResetsIt() throws Exception {
        var path = directory.resolve("keys.json");
        var store = new ProgramKeyStore(path);
        var policy = new ProgramKeyStore.Policy("client", Set.of(), true, List.of());
        var created = store.create(policy);
        var id = created.key().id();
        for (String external : List.of("192.0.2.1", "172.32.0.1", "2001:db8::1", "::ffff:203.0.113.8")) {
            assertThatExceptionOfType(ProgramKeyStore.HttpsRequired.class)
                    .isThrownBy(() -> store.authenticate(created.secret(), external, false));
            assertThat(store.authenticate(created.secret(), external, true)).isNotNull();
        }
        for (String local : List.of("127.0.0.1", "::1", "10.2.3.4", "172.31.0.1", "192.168.1.1", "fd01::1"))
            assertThat(store.authenticate(created.secret(), local, false)).isNotNull();
        assertThatIllegalStateException().isThrownBy(() -> store.transport(id, 1, ProgramKeyStore.Transport.HTTP_ALLOWED));
        store.transport(id, 1, ProgramKeyStore.Transport.REVIEWED);
        assertThatExceptionOfType(ProgramKeyStore.HttpsRequired.class)
                .isThrownBy(() -> store.authenticate(created.secret(), "203.0.113.2", false));
        assertThatIllegalStateException().isThrownBy(() -> store.transport(id, 1, ProgramKeyStore.Transport.HTTP_ALLOWED));
        store.transport(id, 2, ProgramKeyStore.Transport.HTTP_ALLOWED);
        assertThat(new ProgramKeyStore(path).authenticate(created.secret(), "203.0.113.2", false)).isNotNull();
        store.update(id, 3, policy);
        assertThatExceptionOfType(ProgramKeyStore.HttpsRequired.class)
                .isThrownBy(() -> store.authenticate(created.secret(), "203.0.113.2", false));
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
        assertThat(store.authenticate(created.secret(), "198.51.100.1", true).policy().permissions()).isEmpty();
        store.revoke(created.key().id(), 2);
        assertThat(store.authenticate(created.secret(), "192.0.2.1", true)).isNull();
        assertThat(new ProgramKeyStore(file).authenticate(created.secret(), "192.0.2.1", true)).isNull();
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
        assertThat(store.authenticate(created.secret(), "192.0.2.1", true)).isNotNull();
        Files.delete(file); Files.writeString(file, "{broken");
        var damaged = new ProgramKeyStore(file);
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(damaged::list);
        assertThat(Files.readString(file)).isEqualTo("{broken");
        Files.write(file, previous);
        assertThat(damaged.authenticate(created.secret(), "192.0.2.1", true)).isNotNull();
    }

    @Test
    @DisplayName("空限制列表与隐式通配权限不能扩大授权")
    void rejectsAmbiguousPolicy() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramKeyStore.Policy("worker", Set.of(), false, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramKeyStore.Policy("worker", Set.of("example:*"), true, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramKeyStore.Policy("worker", Set.of(), true, List.of("127.0.0.1")));
    }
}
