package top.sywyar.pixivdownload.config.credential;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialDefinitionResolver;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentialException;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginCredentialsFactory;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("无 GUI 的 owner 凭据读写与资源生命周期")
class OwnerPluginCredentialsTest {
    @TempDir Path temporary;

    @AfterEach
    void restore() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
    }

    private PluginCredentialStore store() {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temporary.toString());
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x47);
        return new PluginCredentialStore(PluginCredentialKeyMaterial.forTesting(key, key));
    }

    private PluginCredentialDefinitionResolver definitions() {
        return new PluginCredentialDefinitionResolver(new PluginRegistry(List.of(
                plugin("owner"), plugin("another"))));
    }

    private static PixivFeaturePlugin plugin(String owner) {
        return new PixivFeaturePlugin() {
            public String id() { return owner; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.description"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<String> credentialKeys() { return List.of(owner + ".token", owner + ".refresh"); }
        };
    }

    @Test
    @DisplayName("真实 child context 无 GUI 声明可更新、删除、重开读取密文，另一 owner 与旧句柄被拒绝")
    void encryptedCredentialsAcrossContextReplacement() throws Exception {
        var store = store();
        var tasks = new PluginRuntimeTaskRegistry();
        var credentialsFactory = new OwnerPluginCredentials(store, definitions());
        var contextFactory = new PluginApplicationContextFactory(new PluginStreamRegistry(), tasks);
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.registerBean(PluginCredentialsFactory.class, () -> credentialsFactory);
            parent.refresh();
            var module = new PluginContextModule("owner", getClass().getClassLoader(), List.of());
            try (var child = contextFactory.create(parent, module)) {
                var first = child.getBean(PluginCredentials.class);
                first.update(Map.of("owner.token", "neutral-secret", "owner.refresh", "second-secret"));
                assertThat(first.read("owner.token")).contains("neutral-secret");
                assertThat(Files.readString(RuntimeFiles.peekPluginCredentialPath("owner"), StandardCharsets.UTF_8))
                        .doesNotContain("neutral-secret", "second-secret", "owner.token");
                var another = credentialsFactory.create("another", tasks.resourcesForPlugin("another"));
                assertThatThrownBy(() -> another.read("owner.token"))
                        .isInstanceOf(PluginCredentialException.class).hasMessage("PLUGIN_CREDENTIAL_UNDECLARED");
                assertThatThrownBy(() -> first.update(Map.of("owner.token", "changed", "another.token", "wrong")))
                        .hasMessage("PLUGIN_CREDENTIAL_UNDECLARED");
                assertThat(first.read("owner.token")).contains("neutral-secret");
                var drain = tasks.prepareQuiesce("owner");
                tasks.closeResources("owner", drain);
                tasks.resume("owner");
                try (var replacement = contextFactory.create(parent, module)) {
                    var second = replacement.getBean(PluginCredentials.class);
                    assertThat(second.read("owner.token")).contains("neutral-secret");
                    assertThatThrownBy(() -> first.read("owner.token")).hasMessage("PLUGIN_CREDENTIAL_CLOSED");
                    assertThatThrownBy(() -> first.delete("owner.token")).hasMessage("PLUGIN_CREDENTIAL_CLOSED");
                    second.delete("owner.token");
                    assertThat(second.read("owner.token")).isEmpty();
                    assertThat(second.read("owner.refresh")).contains("second-secret");
                }
            }
        }
    }

    @Test
    @DisplayName("撤回等待已接纳凭据写入完成，旧代际不得重新接纳")
    void drainsAdmittedWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tasks = new PluginRuntimeTaskRegistry();
        var store = mock(PluginCredentialStore.class);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
            return null;
        }).when(store).update(eq("owner"), anyMap());
        var credentials = new OwnerPluginCredentials(store, definitions())
                .create("owner", tasks.resourcesForPlugin("owner"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var work = executor.submit(() -> credentials.update(Map.of("owner.token", "value")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = tasks.prepareQuiesce("owner");
            assertThat(drain.isDrained()).isFalse();
            assertThatThrownBy(() -> credentials.read("owner.token")).hasMessage("PLUGIN_CREDENTIAL_CLOSED");
            assertThatThrownBy(() -> tasks.resume("owner")).isInstanceOf(IllegalStateException.class);
            release.countDown();
            work.get(5, TimeUnit.SECONDS);
            assertThat(drain.isDrained()).isTrue();
            tasks.closeResources("owner", drain);
            tasks.resume("owner");
            assertThatThrownBy(() -> credentials.update(Map.of())).hasMessage("PLUGIN_CREDENTIAL_CLOSED");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("超限更新不写入，存储异常不泄漏秘密或 cause")
    void capacityAndFailureIsolation() throws Exception {
        var store = mock(PluginCredentialStore.class);
        var credentials = new OwnerPluginCredentials(store, definitions())
                .create("owner", new PluginRuntimeTaskRegistry().resourcesForPlugin("owner"));
        assertThatThrownBy(() -> credentials.update(Map.of("owner.token", "字".repeat(22000))))
                .hasMessage("PLUGIN_CREDENTIAL_CAPACITY");
        verifyNoInteractions(store);
        when(store.readAll("owner")).thenThrow(new IOException("neutral-secret private-path"));
        assertThatThrownBy(() -> credentials.read("owner.token"))
                .hasMessage("PLUGIN_CREDENTIAL_STORAGE_FAILED").hasNoCause();
    }
}
