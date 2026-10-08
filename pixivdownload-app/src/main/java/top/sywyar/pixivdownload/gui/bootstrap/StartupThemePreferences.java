package top.sywyar.pixivdownload.gui.bootstrap;

import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** 保存已验证 provider 的主题深浅语义，避免为了首帧提前加载插件。 */
public final class StartupThemePreferences {
    static final int MAX_CACHE_BYTES = 64 * 1024;

    private StartupThemePreferences() { }

    static String read() {
        return read(RuntimeFiles.peekConfigYamlPath(), RuntimeFiles.startupThemeCachePath());
    }

    static String read(Path config, Path cache) {
        try {
            var preferences = new ConfigFileEditor(config).readAll(List.of("app.theme", "app.gui-provider"));
            String theme = preferences.getOrDefault("app.theme", "system").trim().toLowerCase(Locale.ROOT);
            if (theme.equals("light") || theme.equals("dark")) return theme;
            if (theme.isEmpty() || theme.equals("system")) return "system";
            if (!Files.isRegularFile(cache)) return "system";
            byte[] bytes;
            try (var input = Files.newInputStream(cache)) { bytes = input.readNBytes(MAX_CACHE_BYTES + 1); }
            if (bytes.length > MAX_CACHE_BYTES) return "system";
            var values = new Properties();
            values.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            if (!preferences.getOrDefault("app.gui-provider", "").trim().equals(values.getProperty("provider"))) return "system";
            String appearance = values.getProperty("theme." + theme, "system");
            return appearance.equals("light") || appearance.equals("dark") ? appearance : "system";
        } catch (IOException | RuntimeException unavailable) {
            return "system";
        }
    }

    public static void remember(Path config, String selectedProvider, List<DesktopUiPluginSnapshot> plugins) {
        remember(config, RuntimeFiles.startupThemeCachePath(), selectedProvider, plugins);
    }

    static void remember(Path config, Path cache, String selectedProvider, List<DesktopUiPluginSnapshot> plugins) {
        Path temporary = null;
        try {
            String requested = new ConfigFileEditor(config).read("app.gui-provider");
            var values = new Properties();
            values.setProperty("provider", requested == null ? "" : requested.trim());
            plugins.stream().filter(plugin -> plugin.desktopUiProvider() && plugin.id().equals(selectedProvider))
                    .flatMap(plugin -> plugin.themes().stream()).forEach(theme -> values.setProperty(
                            "theme." + theme.themeId().trim().toLowerCase(Locale.ROOT),
                            theme.appearance().name().toLowerCase(Locale.ROOT)));
            var output = new StringWriter();
            values.store(output, null);
            byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_CACHE_BYTES) return;
            Files.createDirectories(cache.toAbsolutePath().getParent());
            temporary = Files.createTempFile(cache.toAbsolutePath().getParent(), "startup-theme-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException unavailable) {
            // 外观缓存可丢弃，写入失败不能改变配置保存结果或阻止桌面启动。
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
