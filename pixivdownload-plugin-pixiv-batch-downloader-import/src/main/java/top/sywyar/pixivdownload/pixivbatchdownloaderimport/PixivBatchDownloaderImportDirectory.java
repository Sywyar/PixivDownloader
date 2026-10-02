package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDirectorySuggestion;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDirectorySuggestionSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Observations provide a candidate only; the owner configuration remains the authority. */
public final class PixivBatchDownloaderImportDirectory implements DesktopDirectorySuggestionSource {
    static final String KEY = "pixiv-batch-downloader-import.source-root";
    private final Path configFile;
    private DesktopDirectorySuggestion candidate;

    PixivBatchDownloaderImportDirectory(Path configFile) {
        this.configFile = configFile;
    }

    String configuredRoot() throws IOException {
        if (!Files.exists(configFile)) return "";
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) { properties.load(reader); }
        return properties.getProperty(KEY, "").trim();
    }

    synchronized void observe(JsonNode input) {
        if (candidate != null) return;
        if (input == null || !input.isObject()) throw new IllegalArgumentException("INVALID_OBSERVATION");
        JsonNode files = input.path("files");
        if (!files.isArray() || files.isEmpty() || files.size() > 1000) return;
        Path root = null;
        for (JsonNode file : files) {
            String value = file.path("path").asText("");
            if (value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)) return;
            Path path = Path.of(value);
            if (!path.isAbsolute()) return;
            Path parent = path.normalize().getParent();
            if (parent == null) return;
            if (root == null) root = parent;
            while (root != null && !parent.startsWith(root)) root = root.getParent();
            if (root == null) return;
        }
        PixivBatchDownloaderImportObservation.parse(input, root);
        candidate = new DesktopDirectorySuggestion(UUID.randomUUID().toString(), KEY, root.toString());
    }

    @Override
    public synchronized Optional<DesktopDirectorySuggestion> directorySuggestion() {
        try {
            return configuredRoot().isBlank() ? Optional.ofNullable(candidate) : Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
