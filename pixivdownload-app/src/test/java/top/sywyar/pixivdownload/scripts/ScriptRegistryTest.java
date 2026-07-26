package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.userscript.UserscriptArtifact;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ScriptRegistry 油猴脚本物化与元数据解析测试")
class ScriptRegistryTest {

    @TempDir
    private Path tempDir;

    /**
     * core-only 构建不再携带下载工作台 userscript contribution；真实脚本物化由外置
     * download-workbench 模块测试覆盖。
     */
    private static ScriptRegistry registryFromCoreOnlyContribution() {
        UserscriptRegistry userscriptRegistry =
                new UserscriptRegistry(new PluginRegistry(List.of()));
        return new ScriptRegistry(TestI18nBeans.appMessages(), userscriptRegistry);
    }

    @Test
    @DisplayName("core-only 没有 userscript contribution 时脚本列表为空")
    void scriptsIsEmptyWithoutContribution() {
        ScriptRegistry registry = registryFromCoreOnlyContribution();
        assertThat(registry.scripts()).isEmpty();
    }

    @Test
    @DisplayName("带 UTF-8 BOM 的多合一脚本仍可解析 name/version/description，且忽略 @description:en")
    void parseScriptMetadataHandlesBomAndLocalizedDescription() throws Exception {
        String content = """
                \uFEFF// ==UserScript==
                // @name         Pixiv All-in-One Downloader
                // @version      1.2.3
                // @description  中文描述
                // @description:en  English description
                // ==/UserScript==
                (function(){})();
                """;

        UserscriptArtifact artifact =
                ScriptRegistry.parseScript("all-in-one", "Pixiv All-in-One.user.js", content);

        assertEquals("all-in-one", artifact.id());
        assertEquals("Pixiv All-in-One Downloader", artifact.displayName());
        assertEquals("1.2.3", artifact.version());
        assertEquals("中文描述", artifact.description());
        assertEquals(content, artifact.content());
    }

    @Test
    @DisplayName("refresh 一次性读取完整 UTF-8 文本且注销后刷新清空")
    void refreshMaterializesCompleteUtf8Content() throws Exception {
        Path directory = tempDir.resolve("static/userscripts");
        Files.createDirectories(directory);
        Path script = directory.resolve("External.user.js");
        String first = script("外置脚本", "1.0.0", "第一份内容");
        String second = script("外置脚本", "2.0.0", "第二份内容");
        Files.writeString(script, first, StandardCharsets.UTF_8);

        UserscriptRegistry sources = new UserscriptRegistry(new PluginRegistry(List.of()));
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            sources.register("external", loader, List.of(new UserscriptContribution(
                    "external", "classpath:/static/userscripts/External.user.js")));
            ScriptRegistry registry = new ScriptRegistry(TestI18nBeans.appMessages(), sources);

            assertThat(registry.scripts()).singleElement()
                    .satisfies(artifact -> {
                        assertThat(artifact.displayName()).isEqualTo("外置脚本");
                        assertThat(artifact.version()).isEqualTo("1.0.0");
                        assertThat(artifact.content()).isEqualTo(first);
                    });

            Files.writeString(script, second, StandardCharsets.UTF_8);
            assertThat(registry.scripts()).singleElement()
                    .extracting(UserscriptArtifact::content)
                    .as("源文件变化不能绕过 refresh 改写当前快照")
                    .isEqualTo(first);
            registry.refresh();
            assertThat(registry.scripts()).singleElement()
                    .extracting(UserscriptArtifact::content)
                    .isEqualTo(second);

            sources.unregister("external");
            registry.refresh();
            assertThat(registry.scripts()).isEmpty();
        }
    }

    @Test
    @DisplayName("单个精确资源缺失时隔离该项并保留同一批次的健康脚本")
    void missingDeclaredResourceIsIsolated() throws Exception {
        Path directory = tempDir.resolve("static/userscripts");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("Healthy.user.js"),
                script("健康脚本", "1.0.0", "healthy"),
                StandardCharsets.UTF_8);

        UserscriptRegistry sources = new UserscriptRegistry(new PluginRegistry(List.of()));
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            sources.register("external", loader, List.of(
                    new UserscriptContribution(
                            "healthy", "classpath:/static/userscripts/Healthy.user.js"),
                    new UserscriptContribution(
                            "generated", "classpath:/static/userscripts/Generated.user.js")));

            ScriptRegistry registry = new ScriptRegistry(TestI18nBeans.appMessages(), sources);

            assertThat(registry.scripts()).singleElement()
                    .satisfies(artifact -> {
                        assertThat(artifact.id()).isEqualTo("healthy");
                        assertThat(artifact.displayName()).isEqualTo("健康脚本");
                        assertThat(artifact.content()).contains("// healthy");
                    });
        }
    }

    @Test
    @DisplayName("并发刷新串行发布且较早物化结果不能覆盖较新声明")
    void concurrentRefreshesPublishLatestSourceInCallOrder() throws Exception {
        Path oldRoot = tempDir.resolve("old");
        Path newRoot = tempDir.resolve("new");
        writeScript(oldRoot, "旧脚本", "1.0.0", "old");
        writeScript(newRoot, "新脚本", "2.0.0", "new");

        UserscriptRegistry sources = new UserscriptRegistry(new PluginRegistry(List.of()));
        ScriptRegistry registry = new ScriptRegistry(TestI18nBeans.appMessages(), sources);
        CountDownLatch oldScanStarted = new CountDownLatch(1);
        CountDownLatch releaseOldScan = new CountDownLatch(1);
        CountDownLatch newScanStarted = new CountDownLatch(1);
        CountDownLatch secondRefreshInvoked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (ObservingUrlClassLoader oldLoader = new ObservingUrlClassLoader(
                oldRoot, oldScanStarted, releaseOldScan);
             ObservingUrlClassLoader newLoader = new ObservingUrlClassLoader(
                     newRoot, newScanStarted, new CountDownLatch(0))) {
            sources.register("old", oldLoader, List.of(new UserscriptContribution(
                    "old", "classpath:/static/userscripts/External.user.js")));
            Future<?> oldRefresh = executor.submit(registry::refresh);
            assertThat(oldScanStarted.await(5, TimeUnit.SECONDS)).isTrue();

            sources.unregister("old");
            sources.register("new", newLoader, List.of(new UserscriptContribution(
                    "new", "classpath:/static/userscripts/External.user.js")));
            Future<?> newRefresh = executor.submit(() -> {
                secondRefreshInvoked.countDown();
                registry.refresh();
            });
            assertThat(secondRefreshInvoked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(newScanStarted.await(200, TimeUnit.MILLISECONDS))
                    .as("较新的 refresh 必须等待较早 refresh 完成物化与发布")
                    .isFalse();

            releaseOldScan.countDown();
            oldRefresh.get(5, TimeUnit.SECONDS);
            newRefresh.get(5, TimeUnit.SECONDS);

            assertThat(newScanStarted.getCount()).isZero();
            assertThat(registry.scripts()).singleElement()
                    .satisfies(artifact -> {
                        assertThat(artifact.displayName()).isEqualTo("新脚本");
                        assertThat(artifact.version()).isEqualTo("2.0.0");
                        assertThat(artifact.content()).contains("// new");
                    });
        } finally {
            releaseOldScan.countDown();
            executor.shutdownNow();
        }
    }

    private static void writeScript(Path root, String name, String version, String marker) throws Exception {
        Path directory = root.resolve("static/userscripts");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("External.user.js"),
                script(name, version, marker), StandardCharsets.UTF_8);
    }

    private static String script(String name, String version, String marker) {
        return """
                // ==UserScript==
                // @name         %s
                // @version      %s
                // @description  测试脚本
                // ==/UserScript==
                // %s
                """.formatted(name, version, marker);
    }

    private static final class ObservingUrlClassLoader extends URLClassLoader {

        private final CountDownLatch scanStarted;
        private final CountDownLatch releaseScan;
        private final AtomicBoolean observed = new AtomicBoolean();

        private ObservingUrlClassLoader(
                Path root,
                CountDownLatch scanStarted,
                CountDownLatch releaseScan
        ) throws Exception {
            super(new URL[]{root.toUri().toURL()}, ScriptRegistryTest.class.getClassLoader());
            this.scanStarted = scanStarted;
            this.releaseScan = releaseScan;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws java.io.IOException {
            observeScan();
            return super.getResources(name);
        }

        @Override
        public URL getResource(String name) {
            observeScan();
            return super.getResource(name);
        }

        private void observeScan() {
            if (!observed.compareAndSet(false, true)) {
                return;
            }
            scanStarted.countDown();
            try {
                if (!releaseScan.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("userscript scan release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("userscript scan interrupted", e);
            }
        }
    }
}
