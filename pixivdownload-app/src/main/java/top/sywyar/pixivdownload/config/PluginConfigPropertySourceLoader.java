package top.sywyar.pixivdownload.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.MapPropertySource;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads plugin-owned {@code config/plugins/*.properties} as a low-precedence Spring property source.
 */
@Slf4j
public final class PluginConfigPropertySourceLoader {

    public static final String PROPERTY_SOURCE_NAME = "pixivdownloadPluginConfigProperties";

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private PluginConfigPropertySourceLoader() {
    }

    public static Optional<MapPropertySource> load() {
        Path directory = RuntimeFiles.configDirectory()
                .resolve(RuntimeFiles.PLUGIN_CONFIG_DIR)
                .toAbsolutePath()
                .normalize();
        return load(directory);
    }

    static Optional<MapPropertySource> load(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return Optional.empty();
        }
        if (!Files.isDirectory(directory)) {
            log.warn(message("runtime.log.plugin-config.not-directory", directory));
            return Optional.empty();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> hostKeys = defaultTemplateKeys();
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn(message("runtime.log.plugin-config.list-failed", directory, e.getMessage()), e);
            return Optional.empty();
        }

        for (Path file : files) {
            loadFile(file, values, hostKeys);
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
    }

    private static void loadFile(Path file, Map<String, Object> values, Set<String> hostKeys) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            log.warn(message("runtime.log.plugin-config.file-failed", file, e.getMessage()), e);
            return;
        }

        for (String rawKey : properties.stringPropertyNames()) {
            String key;
            String value;
            try {
                key = requireSafeKey(rawKey);
                value = requireSafeValue(properties.getProperty(rawKey, ""));
            } catch (IOException e) {
                log.warn(message("runtime.log.plugin-config.entry-skipped", file, rawKey, e.getMessage()));
                continue;
            }
            if (values.containsKey(key)) {
                log.warn(message("runtime.log.plugin-config.duplicate-key", file, key));
                continue;
            }
            if (hostKeys.contains(key)) {
                log.warn(message("runtime.log.plugin-config.host-key-skipped", file, key));
                continue;
            }
            if (isCredentialLikeKey(key)) {
                continue;
            }
            values.put(key, value);
        }
    }

    static boolean isCredentialLikeKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(".password")
                || normalized.endsWith(".passwd")
                || normalized.endsWith(".api-key")
                || normalized.endsWith(".apikey")
                || normalized.endsWith(".access-token")
                || normalized.endsWith(".token")
                || normalized.endsWith(".secret")
                || normalized.endsWith(".cookie")
                || normalized.endsWith(".device-key")
                || normalized.endsWith(".webhook-key")
                || normalized.endsWith(".send-key")
                || normalized.endsWith(".key");
    }

    private static Set<String> defaultTemplateKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : DefaultConfigTemplate.build(code -> code).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx > 0) {
                keys.add(trimmed.substring(0, idx).trim());
            }
        }
        return keys;
    }

    private static String requireSafeKey(String key) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IOException("Config key must not be blank");
        }
        String normalized = key.trim();
        if (!SAFE_KEY.matcher(normalized).matches()) {
            throw new IOException("Unsupported config key: " + normalized);
        }
        return normalized;
    }

    private static String requireSafeValue(String value) throws IOException {
        String safe = value == null ? "" : value;
        if (safe.indexOf('\0') >= 0) {
            throw new IOException("Config value contains unsupported NUL character");
        }
        return safe;
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
