package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbeFeaturePlugin;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbePlugin;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.PluginTestProvenance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 GUI bootstrap → Spring → restart 端到端：组装真实外置探针 JAR，经 {@link BackendLifecycleManager} 配置真实
 * {@link BackendLifecycleManager.BackendStarter}（直连 {@link PixivDownloadApplication#start(String[], PluginBootstrapSession)}），
 * 驱动真实 Spring Boot context 的 start → restart → stop，证明：
 * <ul>
 *   <li>启动后 PF4J 探针 load / start 各恰好一次（marker 文件）；</li>
 *   <li>Spring 注入的 PluginBootstrapSession / PluginRuntimeManager / ExternalPluginInstaller Bean 与 PROCESS 会话同实例
 *       （config 未 new 第二个 manager）；</li>
 *   <li>{@link BackendLifecycleManager#restartAsync()} 不产生第二套 PF4J manager / classloader：探针 load / start 计数不变、
 *       manager 同实例、package generation 不变、Spring context 关闭只关 context（探针未被 stop）；</li>
 *   <li>stopAsync → STOPPED；session.close() 释放探针（marker stop 一次）、PF4J manager 清空、探针 JAR 可删；</li>
 *   <li>Registration 清理后静态 starter 恢复默认（不再捕获 session）。</li>
 * </ul>
 *
 * <p>位于 gui 包以访问 {@link BackendLifecycleManager} 的 package-private 测试观测（{@code resetForTests} /
 * {@code usesDefaultStarter}）。真实外置 JAR（非 mock）、有限超时轮询等待状态（禁无限等待 / Thread.sleep 竞态）、
 * {@code server.port=0}、关 setup 自动开浏览器、隔离 config / state / data / plugins 目录。finally 无条件复位
 * BackendLifecycleManager 静态状态、关 context / session、清系统属性。Windows 下先经 session.close() 释放 classloader 再删 JAR。
 */
@DisplayName("真实 bootstrap→Spring→restart：PROCESS 会话与 manager/classloader 在 restart 中复用、不二次 load/start")
class PluginBootstrapBackendRestartTest {

    @TempDir
    Path tempDir;

    private Path pluginsDir;
    private Path probeJar;
    private Path marker;
    private PluginBootstrapSession session;
    private BackendLifecycleManager.Registration registration;

    @BeforeEach
    void isolate() throws IOException {
        // headless 早设：让 BackendLifecycleManager.runOnEdt 在测试线程内联执行（确定性，避免 EDT 未泵送致 stop→start 链路挂起）。
        System.setProperty("java.awt.headless", "true");

        // 隔离运行期目录：config / state / data / plugins 全部指向 temp 子目录
        pluginsDir = tempDir.resolve("plugins");
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("config").toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, tempDir.resolve("state").toString());
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("data").toString());
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, pluginsDir.toString());

        probeJar = stageProbeJar(pluginsDir);
        marker = tempDir.resolve("probe-events.log");
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());

        BackendLifecycleManager.resetForTests();
    }

    @AfterEach
    void cleanup() {
        try {
            // resetForTests 先有限关闭 / 等待 Spring context 与 worker；确认关闭后才能释放 PROCESS session / classloader。
            BackendLifecycleManager.resetForTests();
            if (registration != null) {
                registration.close();
                registration = null;
            }
            if (session != null) {
                session.close();
                session = null;
            }
        } finally {
            System.clearProperty("bootstrap.probe.marker");
            System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
            System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
            System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        }
    }
    @Test
    @DisplayName("start→RUNNING→restart→RUNNING→stop：探针 load/start 各一次、manager/实例/generation 不变、context 关闭不关会话")
    void realBootstrapSpringRestartReusesProcessSession() throws Exception {
        // 1. 进程级 PROCESS 会话 + 真实 PF4J load/start（探针记录 load=1, start=1）
        session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        PluginRuntimeManager originalManager = session.manager();
        assertMarkerCounts(1, 1, 0);
        assertThat(originalManager.pluginManager()).as("PF4J manager 已就绪").isPresent();
        assertThat(originalManager.generation("bootstrap-probe")).hasValue(1L);

        // 2. 配置真实 BackendStarter：直连 PixivDownloadApplication.start(args, session)，捕获 context 供 Bean 校验
        AtomicReference<ConfigurableApplicationContext> ctxRef = new AtomicReference<>();
        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        // 记录状态转移序列，供 restart 时断言完整 STOPPING→STARTING→RUNNING 链路
        List<BackendLifecycleManager.State> sequence = Collections.synchronizedList(new ArrayList<>());
        BackendLifecycleManager.Listener recorder = snapshot -> sequence.add(snapshot.state());
        String[] args = {"--server.port=0", "--setup.browser.auto-open=false"};
        registration = BackendLifecycleManager.configure(args, failureRef::set,
                backendArgs -> {
                    ConfigurableApplicationContext ctx = PixivDownloadApplication.start(backendArgs, session);
                    ctxRef.set(ctx);
                    return ctx;
                });
        BackendLifecycleManager.addListener(recorder);

        // 3. start → 等 RUNNING
        assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
        awaitState(BackendLifecycleManager.State.RUNNING, 90_000L);
        awaitRecordedState(sequence, BackendLifecycleManager.State.RUNNING, 10_000L);
        assertThat(failureRef.get()).as("后端首次启动不应失败").isNull();

        // 4. 从 Spring context 验证：注入的 session / manager / installer 与 PROCESS 会话同实例（config 未 new 第二个 manager）
        ConfigurableApplicationContext firstCtx = ctxRef.get();
        assertThat(firstCtx).isNotNull();
        assertThat(firstCtx.getBean(PluginBootstrapSession.class)).isSameAs(session);
        assertThat(firstCtx.getBean(PluginRuntimeManager.class)).isSameAs(session.manager());
        assertThat(firstCtx.getBean(ExternalPluginInstaller.class)).isSameAs(session.installer());
        assertMarkerCounts(1, 1, 0); // Spring 接手后未重新 load/start 探针
        ClassLoader classLoaderBefore = probeClassLoader(session.manager());

        // 5. restart → 等 RUNNING
        int firstRunningIdx = lastIndexOf(snapshotSequence(sequence), BackendLifecycleManager.State.RUNNING);
        assertThat(BackendLifecycleManager.restartAsync(null)).isTrue();
        awaitState(BackendLifecycleManager.State.RUNNING, 90_000L);
        assertThat(failureRef.get()).as("后端 restart 不应失败").isNull();

        // 5a. restart 经历完整状态序列：首次 RUNNING 之后依次出现 STOPPING → STARTING（最终 RUNNING 由 awaitState 证实）。
        //     recorder 内联记录（headless），但记录发生在 volatile 状态写入之后，故轮询直到二者就绪再断言相对顺序。
        long seqDeadline = System.currentTimeMillis() + 60_000L;
        int stoppingIdx = -1;
        int startingIdx = -1;
        while (System.currentTimeMillis() < seqDeadline) {
            List<BackendLifecycleManager.State> snap = snapshotSequence(sequence);
            OptionalInt stop = indexOfAfter(snap, BackendLifecycleManager.State.STOPPING, firstRunningIdx);
            OptionalInt start = indexOfAfter(snap, BackendLifecycleManager.State.STARTING, firstRunningIdx);
            if (stop.isPresent() && start.isPresent()) {
                stoppingIdx = stop.getAsInt();
                startingIdx = start.getAsInt();
                break;
            }
            sleepBriefly();
        }
        assertThat(stoppingIdx).as("restart 应进入 STOPPING").isGreaterThan(firstRunningIdx);
        assertThat(startingIdx).as("restart 应在 STOPPING 之后重新进入 STARTING").isGreaterThan(stoppingIdx);

        // 6. restart 前后 PF4J 只 load/start 各一次：marker 不变（Spring context 关闭只关 context、探针未被 stop）
        assertMarkerCounts(1, 1, 0);
        // manager 同一实例、package generation 不变、未创建第二套 PF4J manager、探针 classloader 同一实例
        assertThat(session.manager()).isSameAs(originalManager);
        assertThat(session.manager().generation("bootstrap-probe")).hasValue(1L);
        assertThat(probeClassLoader(session.manager())).isSameAs(classLoaderBefore);
        assertThat(session.manager().pluginManager()).isPresent();
        // 新 context 仍注入同一 PROCESS 会话 / manager
        ConfigurableApplicationContext secondCtx = ctxRef.get();
        assertThat(secondCtx).isNotSameAs(firstCtx);
        assertThat(secondCtx.getBean(PluginBootstrapSession.class)).isSameAs(session);
        assertThat(secondCtx.getBean(PluginRuntimeManager.class)).isSameAs(originalManager);

        // 7. stop → STOPPED
        assertThat(BackendLifecycleManager.stopAsync(null)).isTrue();
        awaitState(BackendLifecycleManager.State.STOPPED, 60_000L);
        // Spring context 关闭仍未 stop 探针（PROCESS 会话未关）
        assertMarkerCounts(1, 1, 0);

        // 8. 清理 Registration：静态 starter 恢复默认（不再捕获 session）
        registration.close();
        registration = null;
        assertThat(BackendLifecycleManager.usesDefaultStarter()).isTrue();

        // 9. 关 PROCESS 会话：探针 stop 一次、PF4J manager 释放、探针 JAR 可删（Windows classloader 已释放）
        session.close();
        assertMarkerCounts(1, 1, 1);
        assertThat(session.manager().pluginManager()).isEmpty();
        assertThat(Files.deleteIfExists(probeJar)).as("探针 JAR 在会话关闭、classloader 释放后应可删").isTrue();
        session = null;
    }

    // --- helpers ---

    private void assertMarkerCounts(int load, int start, int stop) throws IOException {
        String events = Files.readString(marker, StandardCharsets.UTF_8);
        assertThat(countOccurrences(events, "load")).isEqualTo(load);
        assertThat(countOccurrences(events, "start")).isEqualTo(start);
        assertThat(countOccurrences(events, "stop")).isEqualTo(stop);
    }

    private static void awaitState(BackendLifecycleManager.State target, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (BackendLifecycleManager.state() != target && System.currentTimeMillis() < deadline) {
            sleepBriefly();
        }
        assertThat(BackendLifecycleManager.state())
                .as("backend should reach state " + target + " within " + timeoutMillis + " ms"
                        + " (current=" + BackendLifecycleManager.state() + ")")
                .isEqualTo(target);
    }

    private static void awaitRecordedState(List<BackendLifecycleManager.State> sequence,
                                           BackendLifecycleManager.State target,
                                           long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (lastIndexOf(snapshotSequence(sequence), target) < 0
                && System.currentTimeMillis() < deadline) {
            sleepBriefly();
        }
        assertThat(lastIndexOf(snapshotSequence(sequence), target))
                .as("listener should record state " + target)
                .isGreaterThanOrEqualTo(0);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for backend lifecycle state", e);
        }
    }
    /** 在 list 锁内快照 recorder 记录的状态序列（读侧安全）。 */
    private static List<BackendLifecycleManager.State> snapshotSequence(List<BackendLifecycleManager.State> seq) {
        synchronized (seq) {
            return new ArrayList<>(seq);
        }
    }

    /** 快照中某状态最后出现的下标（未出现为 -1）。 */
    private static int lastIndexOf(List<BackendLifecycleManager.State> snap, BackendLifecycleManager.State target) {
        int idx = -1;
        for (int i = 0; i < snap.size(); i++) {
            if (snap.get(i) == target) {
                idx = i;
            }
        }
        return idx;
    }

    /** 快照中在 {@code after} 之后某状态首次出现的下标（未出现为 empty）。 */
    private static OptionalInt indexOfAfter(List<BackendLifecycleManager.State> snap,
                                            BackendLifecycleManager.State target, int after) {
        for (int i = after + 1; i < snap.size(); i++) {
            if (snap.get(i) == target) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** 探针功能插件当前解析用的 PF4J classloader（经运行时管理器动态清点）。 */
    private static ClassLoader probeClassLoader(PluginRuntimeManager manager) {
        return manager.inspectPlugins().installations().stream()
                .filter(i -> "bootstrap-probe".equals(i.id()))
                .map(i -> i.classLoader())
                .findFirst()
                .orElseThrow(() -> new AssertionError("bootstrap-probe not discovered in inventory"));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    /** 把 {@link BackendRestartProbePlugin} + {@link BackendRestartProbeFeaturePlugin} 编译产物组装成 PF4J 可加载的 thin 插件 jar。 */
    private static Path stageProbeJar(Path pluginsDir) throws IOException {
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("bootstrap-probe-1.0.0.jar");
        String props = "plugin.id=bootstrap-probe\nplugin.version=1.0.0\nplugin.requires=1.0\n"
                + "plugin.class=" + BackendRestartProbePlugin.class.getName() + "\n"
                + "plugin.provider=test\nplugin.description=bootstrap probe\n";
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("plugin.properties"));
            zos.write(props.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            addClassEntry(zos, BackendRestartProbePlugin.class);
            addClassEntry(zos, BackendRestartProbeFeaturePlugin.class);
        }
        PluginTestProvenance.writeLocalUpload(pluginsDir, jar, "bootstrap-probe", "1.0.0");
        return jar;
    }

    private static void addClassEntry(ZipOutputStream zos, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream("/" + entry)) {
            assertThat(in).as("class resource must be compiled: " + entry).isNotNull();
            bytes = in.readAllBytes();
        }
        zos.putNextEntry(new ZipEntry(entry));
        zos.write(bytes);
        zos.closeEntry();
    }
}
