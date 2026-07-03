package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.imageclassifier.ThumbnailManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已下载插画的本地文件定位与缩略图缓存：缩略图 / 原图文件解析、缩略图生成与缓存。
 * 文件层定位委托 {@link ArtworkFileLocator}，DB 行查询走 {@link PixivDatabase}。
 * 图片字节的 HTTP serving 由核心 {@code WorkAssetFileController} 经 {@code WorkAssetService} 承接，
 * 本服务只产出文件（{@link #getThumbnailFile} / {@link #getImageFile}），不构造 HTTP 响应体。
 */
@Service
public class ArtworkFileService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final PixivDatabase pixivDatabase;
    private final ArtworkFileLocator artworkFileLocator;

    private final ConcurrentHashMap<String, Object> thumbnailCacheLocks = new ConcurrentHashMap<>();

    public record ThumbnailFile(Path path, String extension) {
    }

    public ArtworkFileService(PixivDatabase pixivDatabase,
                              ArtworkFileLocator artworkFileLocator) {
        this.pixivDatabase = pixivDatabase;
        this.artworkFileLocator = artworkFileLocator;
    }

    public ThumbnailFile getThumbnailFile(Long artworkId, int page) throws IOException {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null || artwork.count() <= page || page < 0) {
            return null;
        }

        File imageFile = resolveThumbnailSourceFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String writeFormat = normalizeThumbnailFormat(getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT));
        Path cachePath = thumbnailCachePath(artworkId, page, writeFormat);
        String lockKey = cachePath.toString();
        Object lock = thumbnailCacheLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                FileTime sourceTime = Files.getLastModifiedTime(imageFile.toPath());
                if (isFreshThumbnailCache(cachePath, sourceTime)) {
                    return new ThumbnailFile(cachePath, writeFormat);
                }
                Files.createDirectories(cachePath.getParent());
                BufferedImage thumbnailImage = ThumbnailManager.getThumbnail(imageFile, -1, -1);
                Path tempPath = Files.createTempFile(cachePath.getParent(), "thumb-", "." + writeFormat);
                try {
                    try (OutputStream out = Files.newOutputStream(tempPath)) {
                        if (!ImageIO.write(thumbnailImage, writeFormat, out)) {
                            throw new IOException("Unsupported thumbnail format: " + writeFormat);
                        }
                    }
                    moveReplacing(tempPath, cachePath);
                    Files.setLastModifiedTime(cachePath, sourceTime);
                } finally {
                    Files.deleteIfExists(tempPath);
                }
            }
        } finally {
            thumbnailCacheLocks.remove(lockKey, lock);
        }
        return new ThumbnailFile(cachePath, writeFormat);
    }

    private File resolveThumbnailSourceFile(ArtworkRecord artwork, int page) {
        File imageFile = resolveImageFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String extension = getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT);
        if (!"webp".equals(extension)) {
            return imageFile;
        }
        String dirPath = resolveArtworkDirectory(artwork);
        String baseName = resolveStoredFileBaseName(artwork, page);
        File thumbFile = Paths.get(dirPath, baseName + "_thumb.jpg").toFile();
        return thumbFile.exists() ? thumbFile : null;
    }

    private Path thumbnailCachePath(Long artworkId, int page, String extension) {
        return RuntimeFiles.galleryThumbnailDirectory()
                .resolve(String.valueOf(artworkId))
                .resolve("p" + page + "." + extension)
                .toAbsolutePath()
                .normalize();
    }

    private boolean isFreshThumbnailCache(Path cachePath, FileTime sourceTime) throws IOException {
        if (!Files.isRegularFile(cachePath) || Files.size(cachePath) <= 0) {
            return false;
        }
        return Files.getLastModifiedTime(cachePath).toMillis() >= sourceTime.toMillis();
    }

    private String normalizeThumbnailFormat(String extension) {
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public File getImageFile(Long artworkId, int page) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null) return null;

        int count = artwork.count();
        if (count <= page || page < 0) return null;

        return resolveImageFile(artwork, page);
    }

    /**
     * 作品目录中是否至少有一页可识别的图片文件。verifyFiles 去重 / 脏记录检测复用此判定，
     * 软删除作品不在此校验（由调用方按 {@code deleted} 短路）。
     */
    public boolean hasArtworkFiles(ArtworkRecord artwork) {
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) {
            return false;
        }
        File directory = new File(directoryPath);
        if (!directory.isDirectory()) {
            return false;
        }
        for (int page = 0; page < Math.max(artwork.count(), 1); page++) {
            File file = resolveImageFile(artwork, page);
            if (file != null && IMAGE_EXTENSIONS.contains(getFileExtension(file.getName()).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private File resolveImageFile(ArtworkRecord artwork, int page) {
        return artworkFileLocator.resolveImageFile(artwork, page);
    }

    private String resolveStoredFileBaseName(ArtworkRecord artwork, int page) {
        return artworkFileLocator.resolveStoredFileBaseName(artwork, page);
    }

    private String resolveArtworkDirectory(ArtworkRecord artwork) {
        return artworkFileLocator.resolveArtworkDirectory(artwork);
    }

    public static File findFileByName(String directoryPath, String fileName) {
        return ArtworkFileLocator.findFileByName(directoryPath, fileName);
    }

    private String getFileExtension(String url) {
        if (!StringUtils.hasText(url)) return "jpg";
        String[] parts = url.split("\\.");
        return parts.length > 1 ? parts[parts.length - 1] : "jpg";
    }
}
