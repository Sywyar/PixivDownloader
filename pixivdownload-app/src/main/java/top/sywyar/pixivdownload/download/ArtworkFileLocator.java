package top.sywyar.pixivdownload.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArtworkFileLocator {

    private static final Set<String> HASHABLE_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final PixivDatabase pixivDatabase;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final StagedFileDeletion stagedFileDeletion;

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
            log.warn(logMessage("download.file.log.author-name-missing", artwork.artworkId()));
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

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String resolveStoredFileAuthorName(ArtworkRecord artwork) {
        Long fileAuthorNameId = artwork.fileAuthorNameId();
        if (fileAuthorNameId == null || fileAuthorNameId <= 0) {
            return null;
        }
        return pixivDatabase.getFileAuthorName(fileAuthorNameId);
    }

    /**
     * 删除一个作品在磁盘上的全部留存文件：每页的图片文件（任意扩展名）与对应的
     * {@code _thumb.jpg} 缩略图、动图的 {@code _thumb.jpg}，以及作品 meta sidecar
     * {@code {artworkId}.meta.json}。这些文件经共享 {@link StagedFileDeletion} <b>原子删除</b>
     * （先暂存再删，任一删除失败回滚到删除前状态）——失败时作品文件原样保留、不会半损坏 / 裂图。
     * 文件全删成功后，若该作品独占的 {@code {rootFolder}/{artworkId}/} 目录已空则一并移除空目录；
     * 经分类器移动到共享目录（{@code move_folder}）时不会误删共享目录。
     *
     * <p>图库缩略图二进制缓存 {@code ./data/gallery_thumbs/{artworkId}/} 是可再生缓存，按
     * best-effort 处理：删失败不计入本方法成败、不阻断数据库清理（不触发 409），仅记日志。
     *
     * <p>磁盘边界守卫（避免污染的 folder / move_folder 把删除范围扩大到 root 之外或共享目录）：
     * 解析后的目录必须有效、非 OS / 驱动盘根、且不等于配置的 {@code download.root-folder} 本身；
     * 否则跳过该步并视为"无需处理"（不算失败）。基于已知文件名前缀（{@code stems}）做枚举式匹配而非递归 walk，
     * 即使目录指向共享路径也只会触碰当前作品命名空间内的文件。
     *
     * @return 文件层清理结果：{@code true} 表示作品文件全部删除成功（或没有可删的文件），
     *         调用方可以继续删除 DB 行；{@code false} 表示有文件因锁定 / 权限不足等原因删除失败、已回滚复原，
     *         调用方必须中止 DB 清理以避免 DB 与磁盘状态不一致。
     */
    public boolean deleteArtworkFiles(ArtworkRecord artwork) {
        if (artwork == null) {
            return true;
        }
        boolean filesDeleted = true;
        String directoryPath = resolveArtworkDirectory(artwork);
        if (StringUtils.hasText(directoryPath)) {
            Path safeDir = resolveSafeArtworkDirectory(directoryPath, artwork.artworkId());
            if (safeDir != null) {
                filesDeleted = stagedFileDeletion.deleteAtomically(resolveArtworkFiles(safeDir, artwork));
                if (filesDeleted) {
                    removeOwnedEmptyDirectory(safeDir, artwork.artworkId());
                }
            }
        }
        // 图库缩略图缓存可再生，best-effort：删失败不影响删除成败、不触发 409。
        deleteGalleryThumbnailCache(artwork.artworkId());
        return filesDeleted;
    }

    /**
     * 校验作品目录在边界上是安全可删的：路径解析成功、非 OS / 驱动盘根、且不等于配置的下载根目录本身。
     * {@code move_folder} 允许指向 {@code download.root-folder} 之外（分类器搬移到用户选定的共享目录），
     * 因此不强制要求目录在 root 内；但绝不允许删除根本身或没有名字层级的"裸根"。
     */
    private Path resolveSafeArtworkDirectory(String directoryPath, long artworkId) {
        Path absolute;
        try {
            absolute = Paths.get(directoryPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn(logMessage("download.file.log.directory-invalid", artworkId, directoryPath));
            return null;
        }
        if (absolute.getNameCount() < 1 || absolute.equals(absolute.getRoot())) {
            log.warn(logMessage("download.file.log.directory-root-refused", artworkId, absolute));
            return null;
        }
        Path downloadRoot;
        try {
            downloadRoot = Paths.get(downloadConfig.getRootFolder()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            downloadRoot = null;
        }
        if (downloadRoot != null && absolute.equals(downloadRoot)) {
            log.warn(logMessage("download.file.log.directory-root-folder-refused", artworkId, absolute));
            return null;
        }
        return absolute;
    }

    /**
     * 解析本作品在目录中实际留存的待删文件：逐页图片与 {@code _thumb} 缩略图、动图 {@code _thumb}，
     * 以及作品 meta sidecar {@code {artworkId}.meta.json}。按文件名前缀（{@code stems}）枚举式匹配，
     * 即使目录是共享分类目录也只会匹配到本作品命名空间内的文件。
     */
    private Set<Path> resolveArtworkFiles(Path directory, ArtworkRecord artwork) {
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
                log.warn(logMessage("download.file.log.filename-parse-failed", artwork.artworkId(), page));
            }
        }
        // 作品 meta sidecar（{artworkId}.meta.json）随作品删除一并清除；按 artworkId 键的 stem，
        // 即使目录是共享分类目录也只触本作品命名空间。
        stems.add(getBaseName(WorkSidecarFiles.fileName(artwork.artworkId())));
        return matchFilesByStems(directory, stems);
    }

    private Set<Path> matchFilesByStems(Path directory, Set<String> stems) {
        if (stems.isEmpty()) {
            return Set.of();
        }
        File[] files = directory.toFile().listFiles();
        if (files == null) {
            return Set.of();
        }
        Set<Path> matched = new LinkedHashSet<>();
        for (File file : files) {
            if (file.isFile() && stems.contains(getBaseName(file.getName()))) {
                matched.add(file.toPath());
            }
        }
        return matched;
    }

    /** 仅当目录名等于 artworkId（即标准的 {@code {rootFolder}/{artworkId}/} 独占目录）且为空时移除，避免误删共享/分类目录。*/
    private void removeOwnedEmptyDirectory(Path dir, long artworkId) {
        Path name = dir.getFileName();
        if (name == null || !name.toString().equals(String.valueOf(artworkId))) {
            return;
        }
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            log.warn(logMessage("download.file.log.remove-empty-dir-failed", dir));
        }
    }

    /** 可再生的图库缩略图缓存清理，由 {@link #deleteArtworkFiles} 按 best-effort 调用（返回值仅供测试断言）。 */
    protected boolean deleteGalleryThumbnailCache(long artworkId) {
        Path cacheDir = RuntimeFiles.galleryThumbnailDirectory().resolve(String.valueOf(artworkId));
        if (!Files.isDirectory(cacheDir)) {
            return true;
        }
        boolean[] allDeleted = {true};
        try (var stream = Files.walk(cacheDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn(logMessage("download.file.log.delete-thumbnail-failed", p));
                    allDeleted[0] = false;
                }
            });
        } catch (IOException e) {
            log.warn(logMessage("download.file.log.clean-thumbnail-cache-failed", cacheDir));
            return false;
        }
        return allDeleted[0];
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
