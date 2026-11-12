package top.sywyar.pixivdownload.novel.config;

import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.work.directory.WorkDirectoryTemplate;
import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** 小说类型拥有最终目录模板；首次初始化保留旧根及 User 布局，此后独立配置。 */
public final class NovelDirectorySettings {
    public static final String ROOT_KEY = "download.novel-directory-template";
    public static final String MODE_PREFIX = "download.novel-mode-templates.";
    public static final String DEFAULT_TEMPLATE = "pixiv-download/novel-{artworkId}";
    public static final String DEFAULT_USER_TEMPLATE = "pixiv-download/{username}/{R18}/novel-{artworkId}";
    private final String root;
    private final Map<String, String> overrides;

    public NovelDirectorySettings(String root, Map<String, String> overrides) {
        WorkDirectoryTemplate.parse(root);
        overrides.forEach((mode, template) -> {
            DownloadAcquisitionMode.fromCode(mode);
            if (!template.isBlank()) WorkDirectoryTemplate.parse(template);
        });
        this.root = root;
        this.overrides = Map.copyOf(overrides);
    }

    public String directoryTemplate(String mode) {
        String selected = mode == null ? "" : overrides.getOrDefault(mode, "");
        return selected.isBlank() ? root : selected;
    }

    public static NovelDirectorySettings initialize(DownloadSettings host, RuntimePathProvider paths, Environment environment)
            throws IOException {
        Path file = paths.configFile("properties");
        synchronized (top.sywyar.pixivdownload.plugin.api.storage.PluginConfigFileLocks.forPath(file)) {
            return initialize(host, file, environment);
        }
    }

    private static NovelDirectorySettings initialize(DownloadSettings host, Path file, Environment environment) throws IOException {
        Properties stored = new Properties();
        String original = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        stored.load(new StringReader(original));
        Properties additions = new Properties();
        if (!stored.containsKey(ROOT_KEY)) additions.setProperty(ROOT_KEY, novelIdentity(host.directoryTemplate(null)));
        for (var mode : DownloadAcquisitionMode.values()) {
            String key = MODE_PREFIX + mode.code();
            if (!stored.containsKey(key)) {
                String inherited = host.directoryOverrides().getOrDefault(mode.code(), "");
                additions.setProperty(key, inherited.isBlank() ? "" : novelIdentity(inherited));
            }
        }
        stored.putAll(additions);
        Map<String, String> modes = new LinkedHashMap<>();
        for (var mode : DownloadAcquisitionMode.values())
            modes.put(mode.code(), environment.getProperty(MODE_PREFIX + mode.code(), stored.getProperty(MODE_PREFIX + mode.code())));
        var settings = new NovelDirectorySettings(environment.getProperty(ROOT_KEY, stored.getProperty(ROOT_KEY)), modes);
        if (!additions.isEmpty()) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            var appended = new StringWriter();
            additions.store(appended, "Novel directory templates");
            Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".novel-config-", ".tmp");
            try {
                Files.writeString(temporary, original + (original.endsWith("\n") || original.isEmpty() ? "" : "\n")
                        + appended, StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                org.slf4j.LoggerFactory.getLogger(NovelDirectorySettings.class)
                        .info("Novel directory configuration initialized: addedKeys={}", additions.size());
            } finally { Files.deleteIfExists(temporary); }
        }
        return settings;
    }

    private static String novelIdentity(String template) {
        var matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_+\\-]*)}").matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            var variable = WorkTemplateVariables.find(matcher.group(1));
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    variable != null && variable.id().equals("artworkId") ? "novel-" + matcher.group() : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
