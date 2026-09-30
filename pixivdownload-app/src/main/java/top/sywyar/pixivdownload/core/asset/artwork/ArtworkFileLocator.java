package top.sywyar.pixivdownload.core.asset.artwork;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArtworkFileLocator {

    private static final Set<String> HASHABLE_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "apng", "mp4", "webm", "zip");

    private final PixivDatabase pixivDatabase;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final StagedFileDeletion stagedFileDeletion;
    private final top.sywyar.pixivdownload.core.asset.ArtworkMediaStore mediaStore;
    private final top.sywyar.pixivdownload.core.asset.ExternalWorkFiles externalFiles;

    public record LocatedArtworkFile(File file, String extension) {
    }

    public boolean isReadOnly(ArtworkRecord artwork) {
        return artwork != null && externalFiles.contains(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId());
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
        return resolveImageFile(artwork, page, true);
    }

    /** 判重必须检查正式产物，不能以缩略图代替下载页面。 */
    public File resolveImageFile(ArtworkRecord artwork, int page, boolean allowThumbnail) {
        if (artwork == null) return null;
        if (externalFiles.contains(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId())) {
            Path file = externalFiles.file(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId(), page);
            return file == null ? null : file.toFile();
        }
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) {
            return null;
        }
        return resolveImageFile(artwork, page, allowThumbnail, resolveStoredFileBaseName(artwork, page));
    }

    /** 单次作品扫描只读取、计算一次完整命名快照。 */
    public List<File> resolveImageFiles(ArtworkRecord artwork, boolean allowThumbnail) {
        int count = Math.max(artwork.count(), 1);
        if (isReadOnly(artwork)) {
            List<Path> paths = externalFiles.files(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId());
            List<File> files = new ArrayList<>(count);
            for (int page = 0; page < count; page++) files.add(page < paths.size() && paths.get(page) != null ? paths.get(page).toFile() : null);
            return files;
        }
        List<String> names = resolveStoredFileBaseNames(artwork, count);
        List<File> files = new ArrayList<>(count);
        for (int page = 0; page < count; page++) files.add(resolveImageFile(artwork, page, allowThumbnail, names.get(page)));
        return files;
    }

    private File resolveImageFile(ArtworkRecord artwork, int page, boolean allowThumbnail, String baseName) {
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) return null;
        String[] extensions = artwork.extensions() == null ? new String[0] : artwork.extensions().split(",");
        LinkedHashSet<String> priority = new LinkedHashSet<>();
        boolean hasManifest = false;
        try {
            var manifest = mediaStore.find(artwork.artworkId(), page);
            hasManifest = manifest.isPresent();
            manifest.filter(ArtworkMediaManifest::originalRetained)
                    .ifPresent(saved -> priority.add(saved.originalExtension()));
        } catch (IOException invalid) {
            log.debug("Cannot read media manifest for {}", artwork.artworkId(), invalid);
        }
        if (!hasManifest && extensions.length == 1 && StringUtils.hasText(extensions[0])) priority.add(extensions[0]);
        priority.addAll(List.of("webp", "png", "jpg", "jpeg", "gif", "apng", "mp4", "webm", "zip"));
        for (String extension : priority) {
            if (allowThumbnail && extension.equals("zip")) continue;
            Path file = Paths.get(directoryPath, baseName + "." + extension);
            if (Files.isRegularFile(file)) return file.toFile();
        }
        if (!allowThumbnail) return null;
        Path thumbnail = Paths.get(directoryPath, baseName + "_thumb.jpg");
        return Files.isRegularFile(thumbnail) ? thumbnail.toFile() : null;
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
        if (externalFiles.contains(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId())
                || !Set.of("webp", "mp4", "webm", "apng").contains(extension)) {
            return new LocatedArtworkFile(imageFile, extension);
        }
        String directoryPath = resolveArtworkDirectory(artwork);
        String baseName = resolveStoredFileBaseName(artwork, page);
        File thumbFile = Paths.get(directoryPath, baseName + "_thumb.jpg").toFile();
        return new LocatedArtworkFile(thumbFile.exists() ? thumbFile : imageFile, extension);
    }

    public String resolveStoredFileBaseName(ArtworkRecord artwork, int page) {
        return resolveStoredFileBaseNames(artwork, Math.max(artwork.count(), page + 1)).get(page);
    }

    private List<String> resolveStoredFileBaseNames(ArtworkRecord artwork, int count) {
        List<String> saved = pixivDatabase.getArtworkFileNames(artwork.artworkId());
        if (!saved.isEmpty()) {
            if (saved.size() != count) throw new IllegalStateException("Incomplete artwork filename record");
            for (String name : saved) {
                if (!StringUtils.hasText(name) || name.contains("/") || name.contains("\\")
                        || name.equals(".") || name.equals("..")) throw new UnsafeDeletionPathException(name);
            }
            return saved;
        }
        long fileNameId = artwork.fileName() == null
                ? PixivDatabase.DEFAULT_FILE_NAME_TEMPLATE_ID
                : artwork.fileName();
        String template = pixivDatabase.getFileNameTemplate(fileNameId);
        String authorName = resolveStoredFileAuthorName(artwork);
        if (authorName == null && template != null && template.contains("{author_name}")) {
            log.warn(logMessage("download.file.log.author-name-missing", artwork.artworkId()));
        }
        List<String> baseNames = PixivWorkFileNameFormatter.formatAll(
                template,
                artwork.artworkId(),
                artwork.title(),
                artwork.authorId(),
                authorName,
                artwork.time(),
                count,
                artwork.isAi(),
                artwork.xRestrict(),
                storedMaxLength(artwork.artworkId())
        );
        return baseNames;
    }

    private int storedMaxLength(long artworkId) {
        int length = pixivDatabase.getFileNameMaxLength(artworkId, false);
        return length > 0 ? length : PixivWorkFileNameFormatter.MAX_BASENAME_LENGTH;
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
     * 按数据库记录解析本作品的文件和遗留附属文件，暂存后删除；解析失败时中止整个操作。
     * 失败时尝试回滚，未恢复的副本留在暂存区。只移除已空的作品 ID 目录，缩略图缓存清理为 best-effort。
     *
     * @return 文件删除是否成功；false 时调用方不得继续删除数据库记录
     */
    public boolean deleteArtworkFiles(ArtworkRecord artwork) {
        return deleteArtworkFiles(artwork, () -> {});
    }

    /** 暂存文件保留至记录事务完成，异常时由暂存器恢复。 */
    public boolean deleteArtworkFiles(ArtworkRecord artwork, Runnable commitRecord) {
        if (artwork == null || isReadOnly(artwork)) {
            commitRecord.run();
            return true;
        }
        boolean filesDeleted = true;
        String directoryPath = resolveArtworkDirectory(artwork);
        if (StringUtils.hasText(directoryPath)) {
            Path safeDir = resolveSafeArtworkDirectory(directoryPath, artwork.artworkId());
            if (!Files.notExists(safeDir, LinkOption.NOFOLLOW_LINKS)) {
                if (!PlainFilePathGuard.isPlainDirectory(safeDir)) {
                    throw new UnsafeDeletionPathException(safeDir);
                }
                try {
                    filesDeleted = stagedFileDeletion.deleteAtomically(resolveArtworkFiles(safeDir, artwork), commitRecord);
                } catch (IOException e) {
                    log.warn(logMessage("download.file.log.directory-unreadable", artwork.artworkId(), safeDir));
                    filesDeleted = false;
                }
                if (filesDeleted) {
                    removeOwnedEmptyDirectory(safeDir, artwork.artworkId());
                }
            } else {
                commitRecord.run();
            }
        } else {
            throw new UnsafeDeletionPathException(directoryPath);
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
            throw new UnsafeDeletionPathException(directoryPath);
        }
        if (absolute.getNameCount() < 1 || absolute.equals(absolute.getRoot())) {
            log.warn(logMessage("download.file.log.directory-root-refused", artworkId, absolute));
            throw new UnsafeDeletionPathException(absolute);
        }
        Path downloadRoot;
        try {
            downloadRoot = Paths.get(downloadConfig.getRootFolder()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            downloadRoot = null;
        }
        if (downloadRoot != null && absolute.equals(downloadRoot)) {
            log.warn(logMessage("download.file.log.directory-root-folder-refused", artworkId, absolute));
            throw new UnsafeDeletionPathException(absolute);
        }
        return absolute;
    }

    /**
     * 解析本作品在目录中实际留存的待删文件：逐页图片与 {@code _thumb} 缩略图、动图 {@code _thumb}，
     * 以及作品 meta sidecar {@code {artworkId}.meta.json}。按文件名前缀（{@code stems}）枚举式匹配，
     * 即使目录是共享分类目录也只会匹配到本作品命名空间内的文件。
     */
    private Set<Path> resolveArtworkFiles(Path directory, ArtworkRecord artwork) throws IOException {
        Set<String> stems = new HashSet<>();
        try {
            for (String baseName : resolveStoredFileBaseNames(artwork, Math.max(artwork.count(), 1))) {
                stems.add(baseName);
                stems.add(baseName + "_thumb");
                stems.add(baseName + ".media");
                stems.add(baseName + ".frames");
            }
        } catch (Exception failure) {
            throw new IOException("Cannot resolve every artwork page", failure);
        }
        // 作品 meta sidecar（{artworkId}.meta.json）随作品删除一并清除；按 artworkId 键的 stem，
        // 即使目录是共享分类目录也只触本作品命名空间。
        stems.add(getBaseName(WorkSidecarFiles.fileName(artwork.artworkId())));
        return matchFilesByStems(directory, stems);
    }

    private Set<Path> matchFilesByStems(Path directory, Set<String> stems) throws IOException {
        if (stems.isEmpty()) {
            return Set.of();
        }
        Set<Path> matched = new LinkedHashSet<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(path -> stems.contains(getBaseName(path.getFileName().toString())))
                    .forEach(matched::add);
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
