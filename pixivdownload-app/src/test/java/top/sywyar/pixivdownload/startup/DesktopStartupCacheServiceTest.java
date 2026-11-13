package top.sywyar.pixivdownload.startup;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("桌面启动缓存训练生命周期")
class DesktopStartupCacheServiceTest {
    @TempDir Path directory;
    Path jar, plugins, config, root;

    @BeforeEach void prepare() throws Exception {
        jar = Files.writeString(directory.resolve("app.jar"), "host", StandardCharsets.UTF_8);
        Files.createDirectory(directory.resolve("lib"));
        plugins = Files.createDirectory(directory.resolve("plugins"));
        Files.writeString(plugins.resolve("sample.jar"), "original", StandardCharsets.UTF_8);
        config = directory.resolve("config.yaml");
        root = directory.resolve("aot");
    }

    DesktopStartupCacheService service(DesktopStartupCacheService.Trainer train) {
        return new DesktopStartupCacheService(jar, List.of(), plugins, config, directory.resolve("trust.json"), root, (snapshot, started) -> {}, train);
    }

    @Test @DisplayName("发布成功后自动启动复用同一缓存，损坏缓存可以手动重建")
    void publishAndReuse() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        try (var service = service((jar, options, inputs, cache, work, progress) -> {
            runs.incrementAndGet();
            Files.writeString(cache, "cache", StandardCharsets.UTF_8);
        })) {
            service.start();
            await(service);
            assertThat(service.snapshot().state()).isEqualTo(State.SUCCEEDED);
            var store = new DesktopCacheStore(root);
            String key = StartupCacheTraining.identity(jar, List.of(), StartupCacheInputs.capture(plugins, config, directory.resolve("trust.json")));
            Path cache = store.find(key).orElseThrow().cache();
            service.startAutomatically();
            await(service);
            assertThat(service.snapshot().state()).isEqualTo(State.READY);
            assertThat(runs).hasValue(1);
            Files.writeString(cache, "broken", StandardCharsets.UTF_8);
            assertThat(store.find(key)).isEmpty();
            service.startAutomatically();
            await(service);
            assertThat(runs).hasValue(2);
            assertThat(store.find(key)).isPresent();
        }
    }

    @Test @DisplayName("训练期间仅刷新撤销验证时间仍可发布并在下次自动启动复用")
    void publishAcrossRevocationRefresh() throws Exception {
        var trust = new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore(directory.resolve("trust.json"));
        var original = new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore.RevocationSnapshot(
                1, "a".repeat(64), "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", "2026-01-02T00:00:00Z", List.of());
        trust.acceptRevocations("custom", original);
        String key = StartupCacheTraining.identity(jar, List.of(), StartupCacheInputs.capture(plugins, config, directory.resolve("trust.json")));
        AtomicInteger runs = new AtomicInteger();
        var trainer = (DesktopStartupCacheService.Trainer) (jar, options, inputs, cache, work, progress) -> {
            runs.incrementAndGet();
            progress.accept(Phase.RECORDING);
            trust.acceptRevocations("custom", new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore.RevocationSnapshot(
                    original.sequence(), original.documentSha256(), original.generatedTime(), original.nextUpdate(),
                    "2026-01-03T00:00:00Z", original.entries()));
            StartupCacheInputs.verifyCurrent(inputs);
            Files.writeString(cache, "cache", StandardCharsets.UTF_8);
        };
        try (var service = service(trainer)) {
            service.start();
            await(service);
            assertThat(service.snapshot().state()).isEqualTo(State.SUCCEEDED);
            assertThat(service.snapshot().failure()).isEqualTo(Failure.NONE);
        }
        assertThat(new DesktopCacheStore(root).find(key).orElseThrow().cache()).hasContent("cache");
        try (var service = service(trainer)) {
            service.startAutomatically();
            await(service);
            assertThat(service.snapshot().state()).isEqualTo(State.READY);
            assertThat(runs).hasValue(1);
        }
    }

    @Test @DisplayName("训练期间更新插件拒绝发布，旧缓存完整保留")
    void rejectChangedInputs() throws Exception {
        var store = new DesktopCacheStore(root);
        UUID old = UUID.randomUUID();
        try (var lease = store.acquire()) {
            Files.writeString(store.candidate(old), "old", StandardCharsets.UTF_8);
            store.publish(old, "a".repeat(64));
        }
        try (var service = service((jar, options, inputs, cache, work, progress) -> {
            Files.writeString(cache, "new", StandardCharsets.UTF_8);
            Files.writeString(plugins.resolve("sample.jar"), "modified", StandardCharsets.UTF_8);
        })) {
            service.start();
            await(service);
            assertThat(service.snapshot().failure()).isEqualTo(Failure.INPUT_CHANGED);
            assertThat(store.find("a".repeat(64))).isPresent();
            assertThat(Files.readString(store.candidate(old))).isEqualTo("old");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("训练期间仓库准入配置或撤销状态变化时拒绝发布并保留旧缓存")
    void rejectChangedTrust(boolean changeState) throws Exception {
        var store = new DesktopCacheStore(root);
        UUID old = UUID.randomUUID();
        try (var lease = store.acquire()) {
            Files.writeString(store.candidate(old), "old", StandardCharsets.UTF_8);
            store.publish(old, "a".repeat(64));
        }
        try (var service = service((jar, options, inputs, cache, work, progress) -> {
            Files.writeString(cache, "new", StandardCharsets.UTF_8);
            if (changeState) {
                var state = new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore(directory.resolve("trust.json"));
                String now = java.time.Instant.now().toString();
                state.acceptRevocations("custom", new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore.RevocationSnapshot(
                        1, "a".repeat(64), now, now, now, List.of()));
            } else Files.writeString(config, """
                    plugin-catalog.repositories:
                      - id: custom
                        manifest-url: https://example.invalid/catalog.json
                        revocations-url: https://example.invalid/revocations.json
                        publisher-id: changed
                    """, StandardCharsets.UTF_8);
        })) {
            service.start();
            await(service);
            assertThat(service.snapshot().failure()).isEqualTo(Failure.INPUT_CHANGED);
            assertThat(store.find("a".repeat(64))).isPresent();
            assertThat(store.candidate(old)).hasContent("old");
            assertThat(store.candidate(service.snapshot().executionId())).doesNotExist();
        }
    }

    @Test @DisplayName("重复开始合并任务，取消必须匹配执行身份并等待资源释放")
    void cancelExactExecution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        try (var service = service((jar, options, inputs, cache, work, progress) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) {
                release.await(5, TimeUnit.SECONDS);
                throw cancelled;
            } finally { cleaned.set(true); }
        })) {
            UUID id = service.start();
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(service.start()).isEqualTo(id);
            assertThat(service.cancel(UUID.randomUUID())).isFalse();
            assertThat(service.cancel(id)).isTrue();
            assertThat(service.snapshot().state()).isEqualTo(State.STOPPING);
            assertThat(cleaned).isFalse();
            release.countDown();
            await(service);
            assertThat(cleaned).isTrue();
            assertThat(service.snapshot().state()).isEqualTo(State.CANCELLED);
            assertThat(Files.exists(root.resolve("desktop.identity"))).isFalse();
        } finally { release.countDown(); }
    }

    @Test @DisplayName("同一输入自动失败只尝试一次，插件变化与手动操作可重新训练")
    void automaticFailureBackoff() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var trainer = (DesktopStartupCacheService.Trainer) (jar, options, inputs, cache, work, progress) -> {
            attempts.incrementAndGet();
            throw new StartupCacheTraining.TrainingFailure(Phase.RECORDING, 1);
        };
        try (var service = service(trainer)) { service.startAutomatically(); await(service); }
        try (var service = service(trainer)) {
            service.startAutomatically(); await(service);
            assertThat(attempts).hasValue(1);
            Files.writeString(plugins.resolve("new.jar"), "new", StandardCharsets.UTF_8);
            service.startAutomatically(); await(service);
            assertThat(attempts).hasValue(2);
            service.start(); await(service);
            assertThat(attempts).hasValue(3);
        }
    }

    @Test @DisplayName("跨实例锁拒绝并行训练且不清除已发布缓存")
    void sharedLock() throws Exception {
        var store = new DesktopCacheStore(root);
        try (var lease = store.acquire(); var service = service((a, b, c, d, e, f) -> { throw new AssertionError(); })) {
            service.start(); await(service);
            assertThat(service.snapshot().failure()).isEqualTo(Failure.BUSY);
        }
    }

    private static void await(DesktopStartupCacheService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (service.snapshot().active() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(service.snapshot().active()).isFalse();
    }
}
