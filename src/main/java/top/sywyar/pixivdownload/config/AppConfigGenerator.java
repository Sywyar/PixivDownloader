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
                    .append(DefaultConfigTemplate.comment(message(locale, "config.template.section.appendix")))
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
     * 解析默认配置文本（由 {@link DefaultConfigTemplate} 生成），将每个配置项提取为 {@link ConfigBlock}。
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
        return DefaultConfigTemplate.build(code -> message(locale, code));
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
