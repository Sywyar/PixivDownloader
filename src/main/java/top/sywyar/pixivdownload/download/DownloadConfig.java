package top.sywyar.pixivdownload.download;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig {
    private String rootFolder = "pixiv-download";
    private int delayMs = 1000;

    // getters and setters
    public String getRootFolder() { return rootFolder; }
    public void setRootFolder(String rootFolder) { this.rootFolder = rootFolder; }

    public int getDelayMs() { return delayMs; }
    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
}