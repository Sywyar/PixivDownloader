package top.sywyar.pixivdownload.download.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig {
    // getters and setters
    private String rootFolder = "pixiv-download";
    private int delayMs = 1000;

}