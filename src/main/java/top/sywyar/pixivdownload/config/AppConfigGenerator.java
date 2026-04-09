package top.sywyar.pixivdownload.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
public class AppConfigGenerator {

    private static final String CONFIG_FILE = "config.yaml";

    private static final String DEFAULT_CONFIG = """
            # ========================================================
            # Pixiv Download 配置文件
            # 修改后需要重启服务才能生效
            # ========================================================

            server.port: 6999                              # 服务监听端口

            download.root-folder: pixiv-download           # 下载根目录（相对或绝对路径）
            download.user-flat-folder: false               # User 模式目录结构：false=按用户名分目录，true=与 N-Tab 相同的扁平结构

            # ---- 代理配置 ----

            proxy.enabled: true                            # 是否启用 HTTP 代理
            proxy.host: 127.0.0.1                          # 代理服务器地址
            proxy.port: 7890                               # 代理服务器端口

            # ---- 多人模式配置（仅 multi 模式有效）----

            multi-mode.quota.enabled: true                 # 是否启用下载配额限制
            multi-mode.quota.max-artworks: 50              # 每用户每周期最多下载作品数
            multi-mode.quota.reset-period-hours: 24        # 配额重置周期（小时）
            multi-mode.quota.archive-expire-minutes: 60    # 压缩包下载链接有效时间（分钟）
            multi-mode.quota.limit-image: 0                # 单作品图片数上限（0=不限制）；超出后按 ceil(图片数/limit-image) 个作品计算配额

            # 下载后处理模式（三选一）：
            #   pack-and-delete  打包后删除源文件（默认）
            #   never-delete     打包后保留源文件；再次下载同一作品直接返回已完成
            #   timed-delete     打包后保留源文件；超过 delete-after-hours 后自动删除
            multi-mode.post-download-mode: pack-and-delete

            multi-mode.delete-after-hours: 72              # timed-delete 模式：下载后多少小时自动删除（小时）

            multi-mode.request-limit-minute: 300           # 每用户每分钟最大请求次数（0 表示不限制）

            # ---- 登录安全配置 ----

            setup.login-rate-limit-minute: 10              # 每个 IP 每分钟最多允许的登录尝试次数（0 = 不限制）
            """;

    @PostConstruct
    public void generateOrUpdateConfig() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            writeDefaultConfig(configFile);
        } else {
            updateMissingKeys(configFile);
        }
    }

    // ---- 私有方法 ---------------------------------------------------------------

    private void writeDefaultConfig(File configFile) {
        try {
            Files.writeString(configFile.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
            log.info("已生成默认配置文件: {}", configFile.getAbsolutePath());
            log.info("如需修改配置（端口、根目录、配额等），请编辑该文件后重启服务");
        } catch (IOException e) {
            log.warn("生成配置文件失败: {}", e.getMessage());
        }
    }

    /**
     * 对比已有配置与默认配置，将缺失的配置项追加到文件末尾，不影响用户已有的值。
     */
    private void updateMissingKeys(File configFile) {
        try {
            String existing = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            Set<String> existingKeys = extractKeys(existing);

            List<ConfigBlock> missingBlocks = parseDefaultConfigBlocks().stream()
                    .filter(block -> !existingKeys.contains(block.key()))
                    .toList();

            if (missingBlocks.isEmpty()) {
                return;
            }

            StringBuilder appendix = new StringBuilder();
            appendix.append("\n# ---- 以下为自动补全的新增配置项（请按需修改）----\n\n");
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
            log.info("配置文件已自动补全 {} 个新增配置项: {}", newKeys.size(), String.join(", ", newKeys));
            log.info("如需调整，请编辑 {} 后重启服务", configFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("更新配置文件失败: {}", e.getMessage());
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
    private List<ConfigBlock> parseDefaultConfigBlocks() {
        List<ConfigBlock> blocks = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();

        for (String rawLine : DEFAULT_CONFIG.split("\n")) {
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

    /** 默认配置中的一个配置项，包含紧邻其前的注释行和键值行本身。 */
    private record ConfigBlock(String key, List<String> comments, String line) {}
}
