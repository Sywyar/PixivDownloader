package top.sywyar.pixivdownload.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArtworkFileLocator {

    private static final Set<String> HASHABLE_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final PixivDatabase pixivDatabase;

    public record LocatedArtworkFile(File file, String extension) {
    }

    public String resolveArtworkDirectory(ArtworkRecord artwork) {
        if (artwork == null) {
            return null;
        }
        if (StringUtils.hasText(artwork.moveFolder())) {
            return artwork.moveFolder();
        }
        return artwork.folder();
    }

    public File resolveImageFile(ArtworkRecord artwork, int page) {
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) {
            return null;
        }
        String baseName = resolveStoredFileBaseName(artwork, page);
        String[] extensions = artwork.extensions() == null ? new String[0] : artwork.extensions().split(",");
        File imageFile;
        if (extensions.length > 1) {
            imageFile = findFileByName(directoryPath, baseName);
        } else {
            String extension = extensions.length == 0 || !StringUtils.hasText(extensions[0]) ? "jpg" : extensions[0];
            imageFile = Paths.get(directoryPath, baseName + "." + extension).toFile();
        }
        return imageFile != null && imageFile.exists() ? imageFile : null;
    }

    public LocatedArtworkFile resolveHashSourceFile(ArtworkRecord artwork, int page) {
        File imageFile = resolveImageFile(artwork, page);
        if (imageFile == null) {
            return null;
        }
        String extension = getFileExtension(imageFile.getName()).toLowerCase(Locale.ROOT);
        if (!HASHABLE_IMAGE_EXTENSIONS.contains(extension)) {
            return null;
        }
        if (!"webp".equals(extension)) {
            return new LocatedArtworkFile(imageFile, extension);
        }
        String directoryPath = resolveArtworkDirectory(artwork);
        String baseName = resolveStoredFileBaseName(artwork, page);
        File thumbFile = Paths.get(directoryPath, baseName + "_thumb.jpg").toFile();
        return thumbFile.exists() ? new LocatedArtworkFile(thumbFile, "webp") : null;
    }

    public String resolveStoredFileBaseName(ArtworkRecord artwork, int page) {
        long fileNameId = artwork.fileName() == null
                ? ArtworkFileNameFormatter.DEFAULT_TEMPLATE_ID
                : artwork.fileName();
        String template = pixivDatabase.getFileNameTemplate(fileNameId);
        int count = Math.max(artwork.count(), page + 1);
        String authorName = resolveStoredFileAuthorName(artwork);
        if (authorName == null && template != null && template.contains("{author_name}")) {
            log.warn("模板含{author_name}但file_author_name_id为空，作者名将缺失: artworkId={}", artwork.artworkId());
        }
        List<String> baseNames = ArtworkFileNameFormatter.formatAll(
                template,
                artwork.artworkId(),
                artwork.title(),
                artwork.authorId(),
                authorName,
                artwork.time(),
                count,
                artwork.isAi(),
                artwork.xRestrict()
        );
        return baseNames.get(page);
    }

    private String resolveStoredFileAuthorName(ArtworkRecord artwork) {
        Long fileAuthorNameId = artwork.fileAuthorNameId();
        if (fileAuthorNameId == null || fileAuthorNameId <= 0) {
            return null;
        }
        return pixivDatabase.getFileAuthorName(fileAuthorNameId);
    }

    /**
     * 删除一个作品在磁盘上的全部留存文件（best-effort）：每页的图片文件（任意扩展名）与对应的
     * {@code _thumb.jpg} 缩略图、动图的 {@code _thumb.jpg}，以及图库缩略图二进制缓存
     * {@code ./data/gallery_thumbs/{artworkId}/}。最后若该作品独占的 {@code {rootFolder}/{artworkId}/}
     * 目录已空，则一并移除空目录；经分类器移动到共享目录（{@code move_folder}）时不会误删共享目录。
     * 任何单步失败仅记日志，不抛出——DB 行清理不应被文件删除失败阻断。
     */
    public void deleteArtworkFiles(ArtworkRecord artwork) {
        if (artwork == null) {
            return;
        }
        String directoryPath = resolveArtworkDirectory(artwork);
        if (StringUtils.hasText(directoryPath)) {
            Set<String> stems = new HashSet<>();
            int count = Math.max(artwork.count(), 1);
            for (int page = 0; page < count; page++) {
                try {
                    String baseName = resolveStoredFileBaseName(artwork, page);
                    if (StringUtils.hasText(baseName)) {
                        stems.add(baseName);
                        stems.add(baseName + "_thumb");
                    }
                } catch (Exception e) {
                    log.warn("解析作品 {} 第 {} 页文件名失败，跳过该页文件删除", artwork.artworkId(), page);
                }
            }
            deleteFilesWithStems(directoryPath, stems);
            removeOwnedEmptyDirectory(directoryPath, artwork.artworkId());
        }
        deleteGalleryThumbnailCache(artwork.artworkId());
    }

    private void deleteFilesWithStems(String directoryPath, Set<String> stems) {
        if (stems.isEmpty()) {
            return;
        }
        File directory = new File(directoryPath);
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && stems.contains(getBaseName(file.getName()))) {
                if (!file.delete()) {
                    log.warn("删除作品文件失败: {}", file.getAbsolutePath());
                }
            }
        }
    }

    /** 仅当目录名等于 artworkId（即标准的 {@code {rootFolder}/{artworkId}/} 独占目录）且为空时移除，避免误删共享/分类目录。*/
    private void removeOwnedEmptyDirectory(String directoryPath, long artworkId) {
        Path dir = Paths.get(directoryPath);
        Path name = dir.getFileName();
        if (name == null || !name.toString().equals(String.valueOf(artworkId))) {
            return;
        }
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            log.warn("移除作品空目录失败: {}", directoryPath);
        }
    }

    private void deleteGalleryThumbnailCache(long artworkId) {
        Path cacheDir = RuntimeFiles.galleryThumbnailDirectory().resolve(String.valueOf(artworkId));
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (var stream = Files.walk(cacheDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除图库缩略图缓存失败: {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("清理图库缩略图缓存目录失败: {}", cacheDir);
        }
    }

    public static File findFileByName(String directoryPath, String fileName) {
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            return null;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && getBaseName(file.getName()).equals(fileName)) {
                    return file;
                }
            }
        }
        return null;
    }

    private static String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private static String getFileExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) return "jpg";
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 && dotIndex < fileName.length() - 1 ? fileName.substring(dotIndex + 1) : "jpg";
    }
}
