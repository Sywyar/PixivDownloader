package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 已下载插画的本地文件定位与缩略图缓存：缩略图 / 原图文件解析、缩略图生成与缓存。
 * 文件层定位委托 {@link ArtworkFileLocator}，DB 行查询走 {@link PixivDatabase}。
 * 图片字节的 HTTP serving 由核心 {@code WorkAssetFileController} 经 {@code WorkAssetService} 承接，
 * 本服务只产出文件（{@link #getThumbnailFile} / {@link #getImageFile}），不构造 HTTP 响应体。
 */
@Service
public class ArtworkFileService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "apng", "mp4", "webm", "zip");

    private final PixivDatabase pixivDatabase;
    private final ArtworkFileLocator artworkFileLocator;
    private final ArtworkMediaDecoder mediaDecoder;

    static final int MAX_CONCURRENT_THUMBNAILS = 4;
    private final Semaphore thumbnailGenerationSlots = new Semaphore(MAX_CONCURRENT_THUMBNAILS, true);
    private final ConcurrentHashMap<String, ThumbnailLock> thumbnailCacheLocks = new ConcurrentHashMap<>();

    private static final class ThumbnailLock {
        int users;
    }

    public record ThumbnailFile(Path path, String extension) {
    }

    public ArtworkFileService(PixivDatabase pixivDatabase,
                              ArtworkFileLocator artworkFileLocator,
                              ArtworkMediaDecoder mediaDecoder) {
        this.pixivDatabase = pixivDatabase;
        this.artworkFileLocator = artworkFileLocator;
        this.mediaDecoder = mediaDecoder;
    }

    public ThumbnailFile getThumbnailFile(Long artworkId, int page) throws IOException {
        return getThumbnailFile(artworkId, page, 512);
    }

    public ThumbnailFile existingThumbnail(Long artworkId, int page) throws IOException {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null || page < 0 || page >= artwork.count()) return null;
        File source = resolveThumbnailSourceFile(artwork, page);
        if (source == null) return null;
        String format = normalizeThumbnailFormat(getFileExtension(source.getName()).toLowerCase(Locale.ROOT));
        Path cache = thumbnailCachePath(artworkId, page, 512, format);
        return isFreshThumbnailCache(cache, Files.getLastModifiedTime(source.toPath()))
                ? new ThumbnailFile(cache, format) : null;
    }

    public ThumbnailFile getThumbnailFile(Long artworkId, int page, int maximumEdge) throws IOException {
        // 有限尺寸档避免任意请求参数产生无限缓存变体；预览沿用桌面的 1600 像素预算。
        int edge = 128;
        while (edge < maximumEdge && edge < 1600) edge = Math.min(1600, edge * 2);
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null || artwork.count() <= page || page < 0) {
            return null;
        }

        File imageFile = resolveThumbnailSourceFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String writeFormat = normalizeThumbnailFormat(getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT));
        Path cachePath = thumbnailCachePath(artworkId, page, edge, writeFormat);
        FileTime sourceTime = Files.getLastModifiedTime(imageFile.toPath());
        if (isFreshThumbnailCache(cachePath, sourceTime)) {
            return new ThumbnailFile(cachePath, writeFormat);
        }
        String lockKey = cachePath.toString();
        ThumbnailLock lock = thumbnailCacheLocks.compute(lockKey, (key, current) -> {
            ThumbnailLock value = current == null ? new ThumbnailLock() : current;
            value.users++;
            return value;
        });
        try {
            synchronized (lock) {
                sourceTime = Files.getLastModifiedTime(imageFile.toPath());
                if (isFreshThumbnailCache(cachePath, sourceTime)) {
                    return new ThumbnailFile(cachePath, writeFormat);
                }
                try {
                    thumbnailGenerationSlots.acquire();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Thumbnail generation interrupted");
                }
                try {
                    Files.createDirectories(cachePath.getParent());
                    BufferedImage thumbnailImage = mediaDecoder.read(imageFile.toPath(), edge);
                    try {
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
                    } finally {
                        thumbnailImage.flush();
                    }
                } finally {
                    thumbnailGenerationSlots.release();
                }
            }
        } finally {
            thumbnailCacheLocks.computeIfPresent(lockKey, (key, current) -> --current.users == 0 ? null : current);
        }
        return new ThumbnailFile(cachePath, writeFormat);
    }

    private File resolveThumbnailSourceFile(ArtworkRecord artwork, int page) {
        File imageFile = resolveImageFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String extension = getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT);
        if (artworkFileLocator.isReadOnly(artwork) || !Set.of("webp", "mp4", "webm", "apng").contains(extension)) {
            return imageFile;
        }
        String dirPath = resolveArtworkDirectory(artwork);
        String baseName = resolveStoredFileBaseName(artwork, page);
        File thumbFile = Paths.get(dirPath, baseName + "_thumb.jpg").toFile();
        return thumbFile.exists() ? thumbFile : imageFile;
    }

    private Path thumbnailCachePath(Long artworkId, int page, int edge, String extension) {
        return RuntimeFiles.galleryThumbnailDirectory()
                .resolve(String.valueOf(artworkId))
                .resolve("p" + page + "-" + edge + "." + extension)
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
        return Set.of("jpg", "jpeg").contains(extension) ? "jpg" : "png";
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
