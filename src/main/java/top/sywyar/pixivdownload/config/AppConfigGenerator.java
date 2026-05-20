package top.sywyar.pixivdownload.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 项目启动时自动维护 config.yaml：
 * <ul>
 *   <li>文件不存在 → 生成含默认值的配置文件</li>
 *   <li>文件已存在 → 检查是否缺少新增配置项，若有则追加（不覆盖用户已有的值）</li>
 * </ul>
 * 修改 config.yaml 后需重启服务才能生效（Spring 在启动时通过 spring.config.import 读取该文件）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AppConfigGenerator {

    private static final String COMMENT_PREFIX = "# ";
    private static final String HEADER_SEPARATOR = "========================================================";
    private static final int CONFIG_ENTRY_WIDTH = 45;

    private final AppMessages messages;

    @PostConstruct
    public void generateOrUpdateConfig() {
        Locale locale = AppLocale.normalize(Locale.getDefault());
        String defaultConfig = buildDefaultConfig(locale);
        File configFile = RuntimeFiles.resolveConfigYamlPath().toFile();
        if (!configFile.exists()) {
            writeDefaultConfig(configFile, defaultConfig);
        } else {
            updateMissingKeys(configFile, defaultConfig, locale);
        }
    }

    // ---- 私有方法 ---------------------------------------------------------------

    private void writeDefaultConfig(File configFile, String defaultConfig) {
        try {
            Files.writeString(configFile.toPath(), defaultConfig, StandardCharsets.UTF_8);
            log.info(message("config.log.default.generated", configFile.getAbsolutePath()));
            log.info(message("config.log.default.generated.hint"));
        } catch (IOException e) {
            log.warn(message("config.log.default.generate-failed", e.getMessage()));
        }
    }

    /**
     * 对比已有配置与默认配置，将缺失的配置项追加到文件末尾，不影响用户已有的值。
     */
    private void updateMissingKeys(File configFile, String defaultConfig, Locale locale) {
        try {
            String existing = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            Set<String> existingKeys = extractKeys(existing);

            List<ConfigBlock> missingBlocks = parseDefaultConfigBlocks(defaultConfig).stream()
                    .filter(block -> !existingKeys.contains(block.key()))
                    .toList();

            if (missingBlocks.isEmpty()) {
                return;
            }

            StringBuilder appendix = new StringBuilder();
            appendix.append("\n")
                    .append(comment(message(locale, "config.template.section.appendix")))
                    .append("\n\n");
            for (ConfigBlock block : missingBlocks) {
                for (String comment : block.comments()) {
                    appendix.append(comment).append("\n");
                }
                appendix.append(block.line()).append("\n\n");
            }

            Files.writeString(configFile.toPath(),
                    existing.stripTrailing() + "\n" + appendix,
                    StandardCharsets.UTF_8);

            List<String> newKeys = missingBlocks.stream().map(ConfigBlock::key).toList();
            log.info(message("config.log.missing-keys.appended", newKeys.size(), String.join(", ", newKeys)));
            log.info(message("config.log.missing-keys.appended.hint", configFile.getAbsolutePath()));
        } catch (IOException e) {
            log.warn(message("config.log.missing-keys.append-failed", e.getMessage()));
        }
    }

    /**
     * 从配置文件内容中提取已存在的 key 集合。
     * 忽略空行和注释行（以 # 开头）。
     */
    private Set<String> extractKeys(String content) {
        Set<String> keys = new HashSet<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                int idx = trimmed.indexOf(':');
                if (idx > 0) {
                    keys.add(trimmed.substring(0, idx).trim());
                }
            }
        }
        return keys;
    }

    /**
     * 解析 DEFAULT_CONFIG，将每个配置项提取为 {@link ConfigBlock}。
     * <p>
     * 规则：
     * <ul>
     *   <li>空行 → 清空当前待处理注释（section 分隔符后的空行隔断关联）</li>
     *   <li>注释行 → 暂存到 pendingComments</li>
     *   <li>键值行 → 与当前 pendingComments 合并成一个 ConfigBlock，然后清空注释</li>
     * </ul>
     * 效果：紧邻键值行之前的注释块（如多行说明）会随该键追加，而 section 标题注释
     * （后跟空行）不会被错误地关联到下一个配置项。
     */
    private List<ConfigBlock> parseDefaultConfigBlocks(String defaultConfig) {
        List<ConfigBlock> blocks = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();

        for (String rawLine : defaultConfig.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                pendingComments.clear();
            } else if (line.startsWith("#")) {
                pendingComments.add(line);
            } else {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    blocks.add(new ConfigBlock(key, List.copyOf(pendingComments), line));
                    pendingComments.clear();
                }
            }
        }
        return blocks;
    }

    private String buildDefaultConfig(Locale locale) {
        StringBuilder config = new StringBuilder();

        appendComment(config, HEADER_SEPARATOR);
        appendComment(config, message(locale, "config.template.header.title"));
        appendComment(config, message(locale, "config.template.header.restart-required"));
        appendComment(config, HEADER_SEPARATOR);
        appendBlankLine(config);

        appendSetting(config, locale, "server.port: 6999", "config.template.server.port.comment");
        appendBlankLine(config);

        appendSetting(config, locale, "download.root-folder: pixiv-download", "config.template.download.root-folder.comment");
        appendSetting(config, locale, "download.user-flat-folder: false", "config.template.download.user-flat-folder.comment");
        appendBlankLine(config);

        appendSection(config, locale, "config.template.section.proxy");
        appendSetting(config, locale, "proxy.enabled: true", "config.template.proxy.enabled.comment");
        appendSetting(config, locale, "proxy.host: 127.0.0.1", "config.template.proxy.host.comment");
        appendSetting(config, locale, "proxy.port: 7890", "config.template.proxy.port.comment");
        appendBlankLine(config);

        appendSection(config, locale, "config.template.section.multi-mode");
        appendSetting(config, locale, "multi-mode.quota.enabled: true", "config.template.multi-mode.quota.enabled.comment");
        appendSetting(config, locale, "multi-mode.quota.max-artworks: 50", "config.template.multi-mode.quota.max-artworks.comment");
        appendSetting(config, locale, "multi-mode.quota.reset-period-hours: 24", "config.template.multi-mode.quota.reset-period-hours.comment");
        appendSetting(config, locale, "multi-mode.quota.archive-expire-minutes: 60", "config.template.multi-mode.quota.archive-expire-minutes.comment");
        appendSetting(config, locale, "multi-mode.quota.limit-image: 0", "config.template.multi-mode.quota.limit-image.comment");
        appendSetting(config, locale, "multi-mode.quota.max-proxy-requests: 200", "config.template.multi-mode.quota.max-proxy-requests.comment");
        appendBlankLine(config);

        appendComment(config, message(locale, "config.template.multi-mode.post-download-mode.comment"));
        appendIndentedComment(config, message(locale, "config.template.multi-mode.post-download-mode.pack-and-delete"));
        appendIndentedComment(config, message(locale, "config.template.multi-mode.post-download-mode.never-delete"));
        appendIndentedComment(config, message(locale, "config.template.multi-mode.post-download-mode.timed-delete"));
        config.append("multi-mode.post-download-mode: pack-and-delete\n\n");

        appendSetting(config, locale, "multi-mode.delete-after-hours: 72", "config.template.multi-mode.delete-after-hours.comment");
        appendBlankLine(config);

        appendSetting(config, locale, "multi-mode.request-limit-minute: 300", "config.template.multi-mode.request-limit-minute.comment");
        appendSetting(config, locale, "multi-mode.static-resource-request-limit-minute: 1200", "config.template.multi-mode.static-resource-request-limit-minute.comment");
        appendSetting(config, locale, "multi-mode.limit-page: 3", "config.template.multi-mode.limit-page.comment");
        appendBlankLine(config);

        appendSection(config, locale, "config.template.section.login-security");
        appendSetting(config, locale, "setup.login-rate-limit-minute: 10", "config.template.setup.login-rate-limit-minute.comment");
        appendBlankLine(config);

        appendSection(config, locale, "config.template.section.maintenance");
        appendSetting(config, locale, "maintenance.enabled: true", "config.template.maintenance.enabled.comment");
        appendBlankLine(config);

        appendSection(config, locale, "config.template.section.ssl");
        appendSetting(config, locale, "ssl.domain: localhost", "config.template.ssl.domain.comment");
        appendSetting(config, locale, "ssl.type: pem", "config.template.ssl.type.comment");
        appendSetting(config, locale, "server.ssl.enabled: false", "config.template.server.ssl.enabled.comment");
        appendSetting(config, locale, "server.ssl.certificate:", "config.template.server.ssl.certificate.comment");
        appendSetting(config, locale, "server.ssl.certificate-private-key:", "config.template.server.ssl.certificate-private-key.comment");
        appendSetting(config, locale, "server.ssl.key-store-type: JKS", "config.template.server.ssl.key-store-type.comment");
        appendSetting(config, locale, "server.ssl.key-store:", "config.template.server.ssl.key-store.comment");
        appendSetting(config, locale, "server.ssl.key-store-password:", "config.template.server.ssl.key-store-password.comment");
        appendSetting(config, locale, "ssl.http-redirect: false", "config.template.ssl.http-redirect.comment");
        appendSetting(config, locale, "ssl.http-redirect-port: 80", "config.template.ssl.http-redirect-port.comment");
        appendBlankLine(config);

        appendSection(config, locale, "config.template.section.language");
        appendSetting(config, locale, "app.language:", "config.template.app.language.comment");
        appendBlankLine(config);

        appendSection(config, locale, "config.template.section.update");
        appendSetting(config, locale, "update.enabled: true", "config.template.update.enabled.comment");
        appendSetting(config, locale,
                "update.manifest-url: " + top.sywyar.pixivdownload.update.UpdateConfig.DEFAULT_MANIFEST_URL,
                "config.template.update.manifest-url.comment");
        appendSetting(config, locale,
                "update.nightly-manifest-url: " + top.sywyar.pixivdownload.update.UpdateConfig.DEFAULT_NIGHTLY_MANIFEST_URL,
                "config.template.update.nightly-manifest-url.comment");
        appendSetting(config, locale, "update.auto-check: true", "config.template.update.auto-check.comment");
        appendSetting(config, locale,
                "update.check-nightly: " + top.sywyar.pixivdownload.update.UpdateConfig.isCurrentVersionNightly(),
                "config.template.update.check-nightly.comment");

        return config.toString();
    }

    private void appendSection(StringBuilder builder, Locale locale, String titleCode) {
        appendComment(builder, message(locale, titleCode));
        appendBlankLine(builder);
    }

    private void appendSetting(StringBuilder builder, Locale locale, String keyValue, String commentCode) {
        builder.append(String.format(
                Locale.ROOT,
                "%-" + CONFIG_ENTRY_WIDTH + "s # %s%n",
                keyValue,
                message(locale, commentCode)
        ));
    }

    private void appendComment(StringBuilder builder, String text) {
        builder.append(comment(text)).append("\n");
    }

    private void appendIndentedComment(StringBuilder builder, String text) {
        builder.append(comment("  " + text)).append("\n");
    }

    private static void appendBlankLine(StringBuilder builder) {
        builder.append("\n");
    }

    private String comment(String text) {
        return COMMENT_PREFIX + text;
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String message(Locale locale, String code, Object... args) {
        return messages.get(locale, code, args);
    }

    /** 默认配置中的一个配置项，包含紧邻其前的注释行和键值行本身。 */
    private record ConfigBlock(String key, List<String> comments, String line) {}
}
