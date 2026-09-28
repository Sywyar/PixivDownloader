package top.sywyar.pixivdownload.guicompose.model;

import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.platform.mac.SystemB;
import com.sun.jna.platform.unix.LibCAPI.size_t;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.AboutFact;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

/** 关于页只收集明确列出的平台信息，不读取用户目录、环境变量或凭据。 */
final class ComposeApplicationInfo {
    private ComposeApplicationInfo() {}

    static List<AboutFact> platformFacts(DesktopUiHost host) {
        return List.of(
                fact("version", value(host.applicationVersion())),
                fact("channel", channel(host.applicationBuildChannel())),
                fact("mode", label(host.developmentMode() ? "development" : "normal")),
                fact("os", property("os.name")),
                fact("os-version", property("os.version")),
                fact("architecture", property("os.arch")),
                fact("cpu", value(Hardware.CPU)),
                fact("processors", TextToken.raw(Integer.toString(Runtime.getRuntime().availableProcessors()))),
                fact("memory", memory()),
                fact("heap", bytes(Runtime.getRuntime().maxMemory())),
                fact("java", property("java.runtime.version")),
                fact("java-vendor", property("java.vendor")),
                fact("vm", property("java.vm.name")),
                fact("interface", TextToken.raw("Compose Multiplatform")),
                fact("kotlin", TextToken.raw(kotlin.KotlinVersion.CURRENT.toString())),
                fact("launch", label(host.launchedFromExecutable() ? "executable" : "jvm"))
        );
    }

    private static AboutFact fact(String id, TextToken value) {
        return new AboutFact(id, label("platform." + id), value);
    }

    // 型号只读取一次，关于页刷新、翻译与复制共用同一份非敏感硬件事实。
    private static final class Hardware {
        private static final String CPU = cpuModel();
    }

    private static String cpuModel() {
        try {
            if (Platform.isWindows()) {
                return Advapi32Util.registryGetStringValue(
                        WinReg.HKEY_LOCAL_MACHINE,
                        "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                        "ProcessorNameString"
                ).trim();
            }
            if (Platform.isMac()) {
                try (var buffer = new Memory(256)) {
                    var size = new size_t.ByReference(new size_t(buffer.size()));
                    if (SystemB.INSTANCE.sysctlbyname("machdep.cpu.brand_string", buffer, size, null, new size_t(0)) == 0) {
                        return buffer.getString(0, StandardCharsets.UTF_8.name()).trim();
                    }
                }
            }
            if (Platform.isLinux()) {
                try (var reader = Files.newBufferedReader(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
                    return cpuModel(reader);
                }
            }
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            // 原生库或平台字段不可用时，保留其它诊断信息。
        }
        return null;
    }

    static String cpuModel(BufferedReader reader) throws IOException {
        for (String line; (line = reader.readLine()) != null;) {
            int separator = line.indexOf(':');
            if (separator < 0) continue;
            String key = line.substring(0, separator).trim();
            if (key.equals("model name") || key.equals("Hardware")) {
                String model = line.substring(separator + 1).trim();
                if (!model.isBlank()) return model;
            }
        }
        return null;
    }

    private static TextToken memory() {
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) return bytes(os.getTotalMemorySize());
        } catch (RuntimeException | LinkageError unavailable) {
            // 未提供扩展 MXBean 的 JVM 仍可显示其它平台字段。
        }
        return label("unknown");
    }

    static TextToken bytes(long bytes) {
        return bytes > 0 ? TextToken.raw(String.format(Locale.ROOT, "%.1f GiB", bytes / 1073741824.0)) : label("unknown");
    }

    private static TextToken channel(DesktopUiHost.BuildChannel channel) {
        if (channel == null) return label("unknown");
        return label(switch (channel) {
            case LOCAL -> "local";
            case RELEASE -> "release";
            case NIGHTLY -> "nightly";
            case UNKNOWN -> "unknown";
        });
    }

    private static TextToken property(String name) {
        try { return value(System.getProperty(name)); }
        catch (SecurityException denied) { return label("unknown"); }
    }

    private static TextToken value(String value) {
        return value == null || value.isBlank() ? label("unknown") : TextToken.raw(value);
    }

    private static TextToken label(String key) {
        return new TextToken("gui-compose", "gui.compose.about." + key, "", List.of());
    }

}
