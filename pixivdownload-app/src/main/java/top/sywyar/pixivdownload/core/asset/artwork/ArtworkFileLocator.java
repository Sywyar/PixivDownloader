package top.sywyar.pixivdownload.core.asset.artwork;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final DownloadDirectories directories;

    private static final List<String> READ_ORDER = List.of("webp", "png", "jpg", "jpeg", "gif", "apng", "mp4", "webm", "zip");

    public record LocatedArtworkFile(File file, String extension) {
    }

    private enum ReadUse {
        RAW, PREVIEW, THUMBNAIL, HASH;

        boolean prefersOwnedThumbnail(String extension) {
            return switch (extension) {
                case "mp4", "webm" -> this == THUMBNAIL || this == HASH;
                case "webp", "apng" -> this == HASH;
                default -> false;
            };
        }
    }

    public boolean isReadOnly(ArtworkRecord artwork) {
        requireDatabase();
        return artwork != null && externalFiles.contains(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId());
    }

    public boolean hasCompletePageIdentity(ArtworkRecord artwork) {
        return externalFiles.hasCompletePageIdentity(artwork.artworkId());
    }

    public String resolveArtworkDirectory(ArtworkRecord artwork) {
        return artwork == null ? null : artwork.effectiveFolder();
    }

    public File resolveImageFile(ArtworkRecord artwork, int page) {
        return resolveImageFile(artwork, page, true);
    }

    /** 判重必须检查正式产物，不能以缩略图代替下载页面。 */
    public File resolveImageFile(ArtworkRecord artwork, int page, boolean allowThumbnail) {
        return fileOf(locatePage(artwork, page, allowThumbnail ? ReadUse.PREVIEW : ReadUse.RAW));
    }

    public File resolveThumbnailSourceFile(ArtworkRecord artwork, int page) {
        return fileOf(locatePage(artwork, page, ReadUse.THUMBNAIL));
    }

    private static File fileOf(LocatedArtworkFile located) {
        return located == null ? null : located.file();
    }

    private LocatedArtworkFile locatePage(ArtworkRecord artwork, int page, ReadUse use) {
        requireDatabase();
        if (artwork == null) return null;
        if (externalFiles.contains(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId())) {
            Path file = externalFiles.file(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artwork.artworkId(), page);
            return file == null ? null : new LocatedArtworkFile(file.toFile(), getFileExtension(file.getFileName().toString()).toLowerCase(Locale.ROOT));
        }
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) {
            return null;
        }
        return locatePage(artwork, page, use, resolveStoredFileBaseName(artwork, page));
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
        ReadUse use = allowThumbnail ? ReadUse.PREVIEW : ReadUse.RAW;
        for (int page = 0; page < count; page++) files.add(fileOf(locatePage(artwork, page, use, names.get(page))));
        return files;
    }

    private LocatedArtworkFile locatePage(ArtworkRecord artwork, int page, ReadUse use, String baseName) {
        requireDatabase();
        String directoryPath = resolveArtworkDirectory(artwork);
        if (!StringUtils.hasText(directoryPath)) return null;
        String[] extensions = artwork.extensions() == null ? new String[0] : artwork.extensions().split(",");
        LinkedHashSet<String> priority = new LinkedHashSet<>();
        ArtworkMediaManifest media = null;
        try {
            media = mediaStore.find(artwork.artworkId(), page).orElse(null);
        } catch (IOException invalid) {
            log.debug("Cannot read media manifest for {}", artwork.artworkId(), invalid);
        }
        requireDatabase();
        if (media == null) {
            if (extensions.length == 1 && StringUtils.hasText(extensions[0])) priority.add(extensions[0]);
            priority.addAll(READ_ORDER);
        } else {
            if (media.originalRetained()) priority.add(media.originalExtension());
            READ_ORDER.stream().filter(media.extensions()::contains).forEach(priority::add);
        }
        Path thumbnail = use != ReadUse.RAW && media != null && media.thumbnailOwned()
                ? Paths.get(directoryPath, baseName + "_thumb.jpg") : null;
        for (String extension : priority) {
            if (use != ReadUse.RAW && extension.equalsIgnoreCase("zip")) continue;
            Path file = Paths.get(directoryPath, baseName + "." + extension);
            if (Files.isRegularFile(file)) {
                if (thumbnail != null && use.prefersOwnedThumbnail(extension)
                        && Files.isRegularFile(thumbnail, LinkOption.NOFOLLOW_LINKS)) file = thumbnail;
                return new LocatedArtworkFile(file.toFile(), extension.toLowerCase(Locale.ROOT));
            }
        }
        if (thumbnail != null && Files.isRegularFile(thumbnail, LinkOption.NOFOLLOW_LINKS))
            return new LocatedArtworkFile(thumbnail.toFile(), "jpg");
        if (use != ReadUse.RAW && media != null && media.originalRetained() && media.originalExtension().equals("zip")) {
            Path archive = Paths.get(directoryPath, baseName + ".zip");
            if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) return new LocatedArtworkFile(archive.toFile(), "zip");
        }
        return null;
    }

    private void requireDatabase() {
        if (pixivDatabase.isUnavailable()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
    }

    public LocatedArtworkFile resolveHashSourceFile(ArtworkRecord artwork, int page) {
        var source = locatePage(artwork, page, ReadUse.HASH);
        return source != null && HASHABLE_IMAGE_EXTENSIONS.contains(source.extension()) ? source : null;
    }

    /** 重下只替换已登记的媒体和附属产物；保留同名的其它用户文件和只读引用。 */
    public Set<Path> managedOutputs(ArtworkRecord artwork) throws IOException {
        if (isReadOnly(artwork)) return Set.of();
        Path directory = resolveSafeArtworkDirectory(resolveArtworkDirectory(artwork), artwork.artworkId());
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        PlainFilePathGuard.requirePlainParent(directory.resolve(".directory-check"), false);
        var files = recordedOutputs(directory, artwork, OutputUse.REBUILD);
        files.removeIf(file -> Files.notExists(file, LinkOption.NOFOLLOW_LINKS));
        return files;
    }

    /** 归档须覆盖实际待删除文件，并包含每一项必需的登记媒体。 */
    public Set<Path> archiveSources(ArtworkRecord artwork) throws IOException {
        Path directory = resolveSafeArtworkDirectory(resolveArtworkDirectory(artwork), artwork.artworkId());
        PlainFilePathGuard.requirePlainParent(directory.resolve(".directory-check"), false);
        return recordedOutputs(directory, artwork, OutputUse.ARCHIVE);
    }

    private enum OutputUse { REBUILD, DELETE, ARCHIVE, PROTECT }

    private Set<Path> recordedOutputs(Path directory, ArtworkRecord artwork, OutputUse use) throws IOException {
        List<String> names;
        try { names = resolveStoredFileBaseNames(artwork, Math.max(artwork.count(), 1)); }
        catch (DatabaseUnavailableException offline) { throw offline; }
        catch (IllegalStateException unavailableNames) {
            if (use != OutputUse.DELETE && use != OutputUse.PROTECT) throw unavailableNames;
            var failure = incompleteOwnership(artwork);
            failure.initCause(unavailableNames);
            throw failure;
        }
        Set<Path> files = new HashSet<>();
        // Only a single page or a single format establishes the historical page-to-format mapping.
        String formats = artwork.extensions();
        List<String> legacy = formats != null && (use == OutputUse.PROTECT || names.size() == 1 || !formats.contains(","))
                ? List.of(formats.split(",")) : List.of();
        // Missing historical page facts can protect a matching file, but cannot authorize its deletion.
        if (use == OutputUse.PROTECT && (legacy.isEmpty() || !HASHABLE_IMAGE_EXTENSIONS.containsAll(legacy))) legacy = READ_ORDER;
        for (int page = 0; page < names.size(); page++) {
            String base = names.get(page);
            var manifest = mediaStore.find(artwork.artworkId(), page);
            var extensions = manifest.map(ArtworkMediaManifest::extensions).orElse(legacy);
            if (use != OutputUse.REBUILD && (extensions.isEmpty() || !HASHABLE_IMAGE_EXTENSIONS.containsAll(extensions))) {
                requireDatabase();
                if (use == OutputUse.DELETE) throw incompleteOwnership(artwork);
                return Set.of();
            }
            addPageOutputs(files, directory, base, extensions, manifest.map(ArtworkMediaManifest::thumbnailOwned).orElse(false),
                    manifest.isPresent() || use != OutputUse.REBUILD);
            if (use != OutputUse.REBUILD) addExistingSidecar(files, directory.resolve(base + ".media.properties"));
        }
        if (use != OutputUse.REBUILD) addExistingSidecar(files, directory.resolve(WorkSidecarFiles.fileName(artwork.artworkId())));
        requireDatabase();
        return files;
    }

    private static void addPageOutputs(Set<Path> files, Path directory, String base, List<String> extensions,
                                       boolean thumbnail, boolean frameTiming) {
        for (String ext : extensions) {
            if (HASHABLE_IMAGE_EXTENSIONS.contains(ext)) files.add(directory.resolve(base + "." + ext));
        }
        if (thumbnail) files.add(directory.resolve(base + "_thumb.jpg"));
        if (frameTiming && extensions.contains("zip")) files.add(directory.resolve(base + ".frames.properties"));
    }

    /** Serializes ownership checks with imports, path corrections and competing file publications. */
    public DownloadDirectories.DeletionReservation reserveOutputs(long artworkId, Collection<Path> files) throws IOException {
        var byDirectory = files.stream().map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.groupingBy(Path::getParent, Collectors.toSet()));
        synchronized (directories) {
            for (var entry : byDirectory.entrySet()) {
                Path folder = entry.getKey();
                var targets = entry.getValue();
                // A selection can be promoted in one transaction while we read. Check the complete record last.
                for (long id : directories.otherArtworksInDirectory(artworkId, folder, true)) {
                    var partial = pixivDatabase.partialArtwork(id);
                    if (partial == null) continue;
                    for (var page : partial.media().entrySet()) {
                        var media = page.getValue();
                        Set<Path> outputs = new HashSet<>();
                        addPageOutputs(outputs, folder, partial.baseNames().get(page.getKey()), media.extensions(),
                                media.thumbnailOwned(), true);
                        if (!Collections.disjoint(targets, outputs)) throw new DirectoryTemplateException("occupied", "");
                    }
                }
                for (long id : directories.otherArtworksInDirectory(artworkId, folder, false)) {
                    var other = pixivDatabase.getArtwork(id);
                    if (other != null && !other.deleted()
                            && !Collections.disjoint(targets, recordedOutputs(folder, other, OutputUse.PROTECT))) {
                        throw new DirectoryTemplateException("occupied", "");
                    }
                }
            }
            return directories.reserveDeletion(files);
        }
    }

    private static WorkDeletionException incompleteOwnership(ArtworkRecord artwork) {
        return new WorkDeletionException(WorkDeletionException.Reason.INCOMPLETE_FILE_OWNERSHIP, WorkType.ARTWORK, artwork.artworkId());
    }

    private static void addExistingSidecar(Set<Path> files, Path path) {
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) files.add(path);
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
        return deleteArtworkFiles(artwork, commitRecord, null);
    }

    public boolean deleteArtworkFiles(ArtworkRecord artwork, Runnable commitRecord, java.util.Map<Path, String> archived) {
        if (artwork == null || isReadOnly(artwork)) {
            if (archived != null) return false;
            commitRecord.run();
            return true;
        }
        boolean filesDeleted = true;
        String directoryPath = resolveArtworkDirectory(artwork);
        if (StringUtils.hasText(directoryPath)) {
            Path safeDir = resolveSafeArtworkDirectory(directoryPath, artwork.artworkId());
            boolean exists = !Files.notExists(safeDir, LinkOption.NOFOLLOW_LINKS);
            if (exists && !PlainFilePathGuard.isPlainDirectory(safeDir)) throw new UnsafeDeletionPathException(safeDir);
            if (!exists && archived != null) return false;
            try {
                var files = archived == null ? recordedOutputs(safeDir, artwork, OutputUse.DELETE) : archived.keySet();
                DownloadDirectories.DeletionReservation reservation;
                try { reservation = reserveOutputs(artwork.artworkId(), files); }
                catch (DirectoryTemplateException occupied) {
                    throw incompleteOwnership(artwork);
                }
                try (reservation) {
                    if (archived != null) filesDeleted = stagedFileDeletion.deleteArchivedFiles(archived, commitRecord);
                    else if (exists) filesDeleted = stagedFileDeletion.deleteAtomically(files, commitRecord);
                    else commitRecord.run();
                }
            } catch (IOException e) {
                log.warn(logMessage("download.file.log.directory-unreadable", artwork.artworkId(), safeDir));
                filesDeleted = false;
            }
            if (exists && filesDeleted) removeOwnedEmptyDirectory(safeDir, artwork.artworkId());
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
