package top.sywyar.pixivdownload.download;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.download.response.ImageResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.imageclassifier.ThumbnailManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已下载插画的本地文件服务：原图 / 缩略图字节 serving、缩略图缓存与文件定位。
 * 文件层定位委托 {@link ArtworkFileLocator}，DB 行查询走 {@link PixivDatabase}。
 */
@Service
public class ArtworkFileService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final PixivDatabase pixivDatabase;
    private final ArtworkFileLocator artworkFileLocator;
    private final AppMessages messages;

    private final ConcurrentHashMap<String, Object> thumbnailCacheLocks = new ConcurrentHashMap<>();

    public record ThumbnailFile(Path path, String extension) {
    }

    public ArtworkFileService(PixivDatabase pixivDatabase,
                              ArtworkFileLocator artworkFileLocator,
                              AppMessages messages) {
        this.pixivDatabase = pixivDatabase;
        this.artworkFileLocator = artworkFileLocator;
        this.messages = messages;
    }

    public ImageResponse getImageResponse(Long artworkId, int page, boolean thumbnail) throws IOException {
        if (thumbnail) {
            ThumbnailFile thumbnailFile = getThumbnailFile(artworkId, page);
            if (thumbnailFile == null) {
                return null;
            }
            byte[] fileBytes = Files.readAllBytes(thumbnailFile.path());
            BufferedImage image = ImageIO.read(thumbnailFile.path().toFile());
            int width = image == null ? 0 : image.getWidth();
            int height = image == null ? 0 : image.getHeight();
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);
            return new ImageResponse(true, base64Image, thumbnailFile.extension(), base64Image.length(), width, height,
                    messages.get("download.image.thumbnail.fetch-success"));
        }

        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null) {
            return null;
        }

        int count = artwork.count();
        if (count <= page || page < 0) {
            return null;
        }

        File imageFile = resolveImageFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String extension = getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT);

        boolean isWebp = "webp".equals(extension);

        // WebP 完整图请求：直接返回原始字节，由 rawfile 端点处理更高效
        // 此处 thumbnail=false 路径保留为备用
        if (isWebp && !thumbnail) {
            byte[] fileBytes = Files.readAllBytes(imageFile.toPath());
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);
            return new ImageResponse(true, base64Image, "webp", base64Image.length(), 0, 0,
                    messages.get("download.image.ugoira.fetch-success"));
        }

        // WebP 缩略图：使用伴随的 _p0_thumb.jpg 文件
        if (isWebp) {
            String dirPath = resolveArtworkDirectory(artwork);
            String baseName = resolveStoredFileBaseName(artwork, page);
            File thumbFile = Paths.get(dirPath, baseName + "_thumb.jpg").toFile();
            if (!thumbFile.exists()) {
                return null;
            }
            imageFile = thumbFile;
            extension = "jpg";
        }

        BufferedImage image;
        if (thumbnail) {
            image = ThumbnailManager.getThumbnail(imageFile, -1, -1);
        } else {
            image = ImageIO.read(imageFile);
        }

        String writeFormat = extension;
        ByteArrayOutputStream bass = new ByteArrayOutputStream();
        ImageIO.write(image, writeFormat, bass);
        String base64Image = Base64.getEncoder().encodeToString(bass.toByteArray());

        return new ImageResponse(true, base64Image, writeFormat, base64Image.length(), image.getWidth(), image.getHeight(),
                messages.get("download.image.thumbnail.fetch-success"));
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
