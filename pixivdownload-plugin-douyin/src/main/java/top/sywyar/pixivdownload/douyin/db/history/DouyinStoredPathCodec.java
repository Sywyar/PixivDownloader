package top.sywyar.pixivdownload.douyin.db.history;

import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;

import java.nio.file.Path;
import java.util.Objects;

/** Stores paths under the current Douyin download root without binding them to one installation path. */
public final class DouyinStoredPathCodec {

    private static final String ROOT_TOKEN = "{douyin}";
    private final DouyinPluginSettingsService settingsService;

    public DouyinStoredPathCodec(DouyinPluginSettingsService settingsService) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
    }

    String encode(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Path root = root();
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            return path.toString();
        }
        Path relative = root.relativize(path);
        return relative.getNameCount() == 0
                ? ROOT_TOKEN
                : ROOT_TOKEN + "/" + relative.toString().replace('\\', '/');
    }

    String resolve(String value) {
        if (value == null || value.isBlank() || !value.startsWith(ROOT_TOKEN)) {
            return value;
        }
        if (value.length() == ROOT_TOKEN.length()) {
            return root().toString();
        }
        if (value.charAt(ROOT_TOKEN.length()) != '/') {
            return value;
        }
        Path root = root();
        Path resolved = root.resolve(value.substring(ROOT_TOKEN.length() + 1)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("Stored Douyin path escapes its download root");
        }
        return resolved.toString();
    }

    private Path root() {
        return settingsService.runtimeSettings().downloadDirectory().toAbsolutePath().normalize();
    }
}
