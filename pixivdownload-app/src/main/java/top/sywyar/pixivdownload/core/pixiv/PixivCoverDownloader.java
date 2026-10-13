package top.sywyar.pixivdownload.core.pixiv;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;

/**
 * 系列封面下载；普通网络失败可跳过，数据库不可用必须停止调用方。
 * 扩展名由响应类型与文件签名共同验证。
 */
@Component
@Slf4j
public class PixivCoverDownloader {

    private static final URI DEFAULT_REFERER = URI.create("https://www.pixiv.net/");

    private final PixivImageDownloader imageDownloader;
    private final top.sywyar.pixivdownload.core.database.DatabaseAvailability database;

    public PixivCoverDownloader(PixivImageDownloader imageDownloader,
            top.sywyar.pixivdownload.core.database.DatabaseAvailability database) {
        this.imageDownloader = imageDownloader;
        this.database = database;
    }

    /**
     * 下载至 {@code targetFolder/baseName.{ext}}；成功返回扩展名，空白、不安全或不可达地址返回 null。
     */
    public String download(String url, Path targetFolder, String baseName, String cookie) {
        database.requireAvailable();
        if (url == null || url.isBlank()) return null;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            log.warn("cover skipped — malformed url: {}", url);
            return null;
        }
        try {
            return imageDownloader.downloadImage(
                    uri,
                    DEFAULT_REFERER,
                    targetFolder.resolve(baseName),
                    cookie,
                    new PixivImageTransferObserver() {
                        @Override public void checkCancelled() { database.requireAvailable(); }
                    });
        } catch (top.sywyar.pixivdownload.core.database.DatabaseUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception e) {
            log.warn("cover download failed: {} — {}", uri, e.getMessage());
            return null;
        }
    }
}
