package top.sywyar.pixivdownload.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import top.sywyar.pixivdownload.core.work.directory.WorkDirectoryTemplate;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** 在配置绑定之前一次性迁移目录语义；不访问作品文件或数据库。 */
public final class DownloadDirectoryConfigMigration implements EnvironmentPostProcessor, Ordered {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DownloadDirectoryConfigMigration.class);
    public static final String VERSION_KEY = "download.directory-template-version";
    public static final String CURRENT_VERSION = "1";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        migrate(RuntimeFiles.resolveConfigYamlPath());
    }

    @Override
    public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER - 1; }

    public static void migrate(Path path) {
        if (!Files.isRegularFile(path)) return;
        var editor = new ConfigFileEditor(path);
        try {
            editor.asConfigFile().withWriteLock(() -> {
                String version = editor.read(VERSION_KEY);
                if (version != null && !CURRENT_VERSION.equals(version))
                    throw new IOException("Unsupported download directory template version");
                if (version == null) {
                    String configured = editor.read("download.root-folder");
                    String root = configured == null || configured.isBlank() ? RuntimeFiles.DEFAULT_DOWNLOAD_ROOT : configured;
                    boolean flat = Boolean.parseBoolean(editor.read("download.user-flat-folder"));
                    boolean alreadyTemplate = root.indexOf('{') >= 0 || root.indexOf('}') >= 0;
                    String base = (alreadyTemplate ? WorkDirectoryTemplate.parse(root).fixedPrefix().toString() : root)
                            .replace('\\', '/').replaceAll("/+$", "") + "/";
                    var values = new LinkedHashMap<String, String>();
                    values.put("download.root-folder", alreadyTemplate ? root : base + "{artwork_id}");
                    for (var mode : DownloadAcquisitionMode.values()) {
                        String key = "download.mode-templates." + mode.code();
                        String existing = editor.read(key);
                        values.put(key, existing != null ? existing :
                                mode == DownloadAcquisitionMode.USER_PROFILE && !flat && !alreadyTemplate
                                        ? base + "{username}/{R18}/{artwork_id}" : "");
                    }
                    WorkDirectoryTemplate.parse(values.get("download.root-folder"));
                    values.put(VERSION_KEY, CURRENT_VERSION);
                    editor.writeAll(values);
                    LOG.info("Download directory configuration migrated: version={}", CURRENT_VERSION);
                }
                // 标记与新值先原子写入，旧键清理中断不会再次迁移或覆盖主动清空的模板。
                editor.removeAll(List.of("download.user-flat-folder"));
            });
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot migrate download directory configuration", failure);
        }
    }
}
