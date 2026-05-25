package top.sywyar.pixivdownload.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;

import java.io.File;
import java.nio.file.Paths;
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
