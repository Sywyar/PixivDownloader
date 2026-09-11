package top.sywyar.pixivdownload.plugin.runtime;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.request.EventRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.runtimeprobe.IsolatedStaticProbeFeaturePlugin;
import top.sywyar.pixivdownload.runtimeprobe.IsolatedStaticProbePlugin;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SDK 对正式准入的指定 worker 连接真实源码断点")
class SdkPluginDebugTest {

    private static final String ID = "isolated-static-probe";
    private static final String PREFIX = "pixivdownload.sdk.debug.";

    @TempDir
    Path temp;

    private final LinkedHashMap<String, String> previous = new LinkedHashMap<>();

    @AfterEach
    void restoreProperties() {
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"matching", "matching-client", "other-id", "other-sha"})
    @DisplayName("调试仅命中指定 ID 与字节，真实 worker 断点恢复后仍能完成 IPC 和退出")
    void debugsOnlySelectedProductionArtifact(String selection) throws Exception {
        Path plugins = Files.createDirectories(temp.resolve("中文 空格/plugins"));
        Path jar = writePlugin(temp.resolve("current.jar"));
        String digest = PluginPackageIntegrity.sha256Hex(jar);
        Path installed;
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
            var unconfirmed = installer.prepareNewTransaction(jar, PluginPackageOrigin.localUnsignedUpload(null));
            assertThat(unconfirmed.result().outcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
            var prepared = installer.prepareNewTransaction(jar, PluginPackageOrigin.localUnsignedUpload(digest));
            assertThat(prepared.readyToCommit()).isTrue();
            var committed = installer.commitTransaction(prepared);
            installer.verifyCommittedTarget(committed);
            // 与正式 process-restart 安装相同：确认文件提交，实际执行由下次启动完成。
            installer.markActivated(committed);
            installer.completeTransaction(committed);
            assertThat(committed.recoveryBlocked()).isFalse();
            installed = prepared.target();
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        property("plugin-id", selection.equals("other-id") ? "other-plugin" : ID);
        property("artifact-sha256", selection.equals("other-sha") ? "0".repeat(64) : digest);
        property("port", Integer.toString(port));
        boolean connect = selection.equals("matching-client");
        property("connect", Boolean.toString(connect));
        var listener = Bootstrap.virtualMachineManager().listeningConnectors().stream()
                .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketListen")).findFirst().orElseThrow();
        var listening = listener.defaultArguments();
        listening.get("port").setValue(Integer.toString(port));
        listening.get("localAddress").setValue("127.0.0.1");
        listening.get("timeout").setValue("30000");
        if (connect) listener.startListening(listening);
        var executor = Executors.newSingleThreadExecutor();
        var manager = new PluginRuntimeManager(plugins, () -> false);
        VirtualMachine vm = null;
        try {
            manager.loadPlugin(installed);
            var initialized = executor.submit(() -> manager.initializePlugin(ID));
            if (selection.startsWith("matching")) {
                vm = connect ? listener.accept(listening) : attach(port);
                var prepare = vm.eventRequestManager().createClassPrepareRequest();
                prepare.addClassFilter(IsolatedStaticProbeFeaturePlugin.class.getName());
                prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                prepare.enable();
                vm.resume();
                boolean hit = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (!hit && System.nanoTime() < deadline) {
                    var events = vm.eventQueue().remove(1000);
                    if (events == null) {
                        continue;
                    }
                    for (var event : events) {
                        if (event instanceof ClassPrepareEvent loaded) {
                            var location = loaded.referenceType().methodsByName("routes").get(0)
                                    .allLineLocations().get(0);
                            var breakpoint = vm.eventRequestManager().createBreakpointRequest(location);
                            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                            breakpoint.enable();
                        } else if (event instanceof BreakpointEvent breakpoint) {
                            assertThat(breakpoint.location().sourceName())
                                    .isEqualTo("IsolatedStaticProbeFeaturePlugin.java");
                            assertThat(breakpoint.location().method().name()).isEqualTo("routes");
                            hit = true;
                        }
                    }
                    events.resume();
                }
                assertThat(hit).as("真实插件源码断点").isTrue();
                vm.dispose();
                vm = null;
            }
            var inventory = initialized.get(30, TimeUnit.SECONDS).inventory();
            assertThat(inventory.installations().get(0).plugin().routes())
                    .hasSize(IsolatedStaticProbeFeaturePlugin.MAX_CONTRIBUTIONS);
            long pid = manager.isolatedWorkerPidForTest(ID);
            assertThat(pid).isPositive().isNotEqualTo(ProcessHandle.current().pid());
            if (!selection.startsWith("matching")) {
                try (ServerSocket unused = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                    assertThat(unused.getLocalPort()).isEqualTo(port);
                }
            }
            manager.startPlugin(ID);
            manager.shutdown();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            if (connect) listener.stopListening(listening);
            if (vm != null) {
                vm.dispose();
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            manager.shutdown();
        }
    }

    private void property(String name, String value) {
        String key = PREFIX + name;
        previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static VirtualMachine attach(int port) throws Exception {
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow();
        var arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue("1000");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        IOException last = null;
        do {
            try {
                return connector.attach(arguments);
            } catch (IOException waiting) {
                last = waiting;
                Thread.sleep(50);
            }
        } while (System.nanoTime() < deadline);
        throw last;
    }

    private static Path writePlugin(Path jar) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        String descriptor = PluginPackageFixtures.pluginProperties(ID,
                SdkVersion.VERSION, SdkVersion.MAJOR + "." + SdkVersion.MINOR,
                IsolatedStaticProbePlugin.class.getName())
                .replace("host-process-full-trust", "declarative-process")
                + "pixiv.kind=feature\npixiv.display-namespace=isolated-static\n"
                + "pixiv.display-name-key=plugin.name\npixiv.description-key=plugin.summary\n";
        entries.put("plugin.properties", PluginPackageFixtures.bytes(descriptor));
        for (Class<?> type : List.of(IsolatedStaticProbePlugin.class, IsolatedStaticProbeFeaturePlugin.class)) {
            String resource = type.getName().replace('.', '/') + ".class";
            try (var input = type.getClassLoader().getResourceAsStream(resource)) {
                entries.put(resource, input.readAllBytes());
            }
        }
        PluginPackageFixtures.writeZip(jar, entries);
        return jar;
    }
}
