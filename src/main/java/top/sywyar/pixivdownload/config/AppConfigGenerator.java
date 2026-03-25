package top.sywyar.pixivdownload.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 项目启动时，若工作目录下不存在 config.yaml，则自动生成含默认值的配置文件。
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

            server:
              port: 6999                    # 服务监听端口

            download:
              root-folder: pixiv-download   # 下载根目录（相对或绝对路径）
              delay-ms: 1000                # 每张图片下载间隔 (ms)

            # ---- 多人模式配额配置（仅 multi 模式有效）----
            multi-mode:
              quota:
                enabled: true              # 是否启用下载配额限制
                max-artworks: 50           # 每用户每周期最多下载作品数
                reset-period-hours: 24     # 配额重置周期（小时）
                archive-expire-minutes: 60 # 压缩包下载链接有效时间（分钟）
            """;

    @PostConstruct
    public void generateIfAbsent() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            try {
                Files.writeString(configFile.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
                log.info("已生成默认配置文件: {}", configFile.getAbsolutePath());
                log.info("如需修改配置（端口、根目录、配额等），请编辑该文件后重启服务");
            } catch (IOException e) {
                log.warn("生成配置文件失败: {}", e.getMessage());
            }
        }
    }
}
