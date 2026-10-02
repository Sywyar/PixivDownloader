package top.sywyar.pixivdownload.gui.controlcenter;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDirectorySuggestion;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDirectorySuggestionSource;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates candidates and saves only a still-empty field belonging to the captured owner. */
public final class PluginDirectorySuggestion implements DirectorySuggestionCapability {
    private final DesktopDirectorySuggestionSource source;
    private final PropertiesConfigFileEditor config;
    private final Set<String> keys;
    private DesktopDirectorySuggestion offered;
    private String dismissed;

    public PluginDirectorySuggestion(String owner, DesktopDirectorySuggestionSource source,
                                     Path configPath, List<GuiConfigFieldContribution> fields) {
        this.source = source;
        this.config = new PropertiesConfigFileEditor(configPath);
        this.keys = fields.stream().filter(field -> field.key().startsWith(owner + ".")
                && field.type() == GuiConfigFieldType.PATH_DIR && !field.sensitive()
                && field.effect() == GuiConfigEffect.HOT_RELOAD && field.defaultValue().isBlank()
                && field.enabledWhen().isEmpty() && field.visibleWhen().isEmpty()
                && field.requiredWhen().isEmpty()).map(GuiConfigFieldContribution::key).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized Optional<DesktopDirectorySuggestion> suggestion() {
        try {
            Optional<DesktopDirectorySuggestion> next = source.directorySuggestion();
            if (next.isEmpty()) { offered = null; return Optional.empty(); }
            DesktopDirectorySuggestion value = next.get();
            if (!keys.contains(value.configurationKey())
                    || !config.readAll(List.of(value.configurationKey())).getOrDefault(value.configurationKey(), "").isBlank()) {
                offered = null;
                return Optional.empty();
            }
            requireDirectory(value.directory());
            offered = value;
            return value.suggestionId().equals(dismissed) ? Optional.empty() : Optional.of(value);
        } catch (IOException failure) {
            offered = null;
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public synchronized void confirm(String suggestionId, String directory) throws IOException {
        DesktopDirectorySuggestion current = suggestion().filter(value -> value.suggestionId().equals(suggestionId))
                .orElseThrow(() -> new IllegalStateException("DIRECTORY_SUGGESTION_STALE"));
        String selected = requireDirectory(directory).toString();
        if (!config.writeIfBlank(current.configurationKey(), selected)) {
            throw new IllegalStateException("DIRECTORY_ALREADY_CONFIGURED");
        }
        offered = null;
    }

    @Override
    public synchronized void dismiss(String suggestionId) {
        if (offered != null && offered.suggestionId().equals(suggestionId)) dismissed = suggestionId;
    }

    private static Path requireDirectory(String value) throws IOException {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.chars().anyMatch(Character::isISOControl)) throw new IOException("INVALID_DIRECTORY");
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !PlainFilePathGuard.isPlainDirectory(path)) throw new IOException("INVALID_DIRECTORY");
            return path.normalize();
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IOException("INVALID_DIRECTORY", invalid);
        }
    }
}
