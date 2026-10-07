package top.sywyar.pixivdownload.core.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRow;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles;
import top.sywyar.pixivdownload.core.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkFileLock;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@link WorkAssetService} 的核心实现。插画侧代理 {@link ArtworkFileService}（缩略图缓存 /
 * 原图定位）与 {@link ArtworkFileLocator}（文件层删除），解析与删除语义同直接调用两者
 * 完全一致。小说侧自管 {@code novel-{id}} 独占目录（守卫 / 递归删除逻辑自小说画廊服务
 * 下沉，逐字保留）：{@code findAsset} 枚举目录下全部常规文件（页号 = 枚举序号），
 * 缩略图恒解析封面 {@code {存储基名}_thumb.{coverExt}}，语义详见接口 javadoc。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalWorkAssetService implements WorkAssetService {

    private final ArtworkFileService artworkFileService;
    private final ArtworkFileLocator artworkFileLocator;
    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final StagedFileDeletion stagedFileDeletion;
    private final top.sywyar.pixivdownload.core.asset.ExternalWorkFiles externalFiles;

    @Override public boolean isReadOnly(WorkType type, long id) { return externalFiles.contains(type, id); }

    @Override
    public void publishFiles(WorkType type, long id, java.util.Map<Path, Path> files, Runnable records) throws IOException {
        try (var ignored = WorkFileLock.acquire(type, id)) {
            stagedFileDeletion.publishFiles(files, records);
        }
    }

    @Override
    public boolean hasCompleteFiles(WorkType type, long id) {
        try (var ignored = WorkFileLock.acquire(type, id)) {
            if (type == WorkType.ARTWORK) {
                ArtworkRecord record = pixivDatabase.getArtwork(id);
                return record != null && !record.deleted() && artworkFileService.hasArtworkFiles(record);
            }
            NovelMetadataRow record = novelMetadataRepository.getNovel(id);
            if (record == null || record.deleted()) return false;
            if (isReadOnly(type, id)) {
                List<Path> files = externalFiles.files(type, id);
                if (files.isEmpty()) return false;
                for (Path file : files) if (!completeFile(file)) return false;
                return true;
            }
            Path directory = exclusiveNovelDirectory(record, false);
            if (directory == null || !StringUtils.hasText(record.extensions())) return false;
            String baseName = resolveStoredNovelBaseName(record);
            for (String extension : record.extensions().split(",")) {
                if (!Set.of("txt", "html", "epub").contains(extension)
                        || !completeFile(directory.resolve(baseName + "." + extension))) return false;
            }
            for (String name : novelMetadataRepository.getImageFileNames(id)) {
                requireFileName(name);
                if (!completeFile(directory.resolve(name))) return false;
            }
            if (StringUtils.hasText(record.coverExt())) {
                String name = baseName + "_thumb." + record.coverExt();
                requireFileName(name);
                if (!completeFile(directory.resolve(name))) return false;
            }
            return true;
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static boolean completeFile(Path file) throws IOException {
        if (file == null || Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        PlainFilePathGuard.requirePlainRegularFile(file);
        if (!Files.isReadable(file)) throw new IOException("Unreadable work file: " + file);
        return Files.size(file) > 0;
    }

    private static void requireFileName(String name) {
        if (!StringUtils.hasText(name) || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) throw new UnsafeDeletionPathException(name);
    }

    @Override
    public Optional<LocalWorkAsset> findAsset(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> findArtworkAsset(workId);
            case NOVEL -> findNovelAsset(workId);
        };
    }

    @Override
    public Optional<WorkAssetFile> existingThumbnail(WorkType workType, long workId, int page) throws IOException {
        if (workType == WorkType.NOVEL) return novelCover(workId);
        var file = artworkFileService.existingThumbnail(workId, page);
        return file == null ? Optional.empty() : Optional.of(new WorkAssetFile(page, file.path(), file.extension()));
    }

    @Override
    public Optional<WorkAssetFile> thumbnail(WorkType workType, long workId, int page) throws IOException {
        return switch (workType) {
            case ARTWORK -> artworkThumbnail(workId, page);
            case NOVEL -> novelCover(workId);
        };
    }

    @Override
    public Optional<WorkAssetFile> thumbnail(WorkType workType, long workId, int page, int maximumEdge) throws IOException {
        if (workType != WorkType.ARTWORK) return thumbnail(workType, workId, page);
        ArtworkFileService.ThumbnailFile file = artworkFileService.getThumbnailFile(workId, page, maximumEdge);
        return file == null ? Optional.empty() : Optional.of(new WorkAssetFile(page, file.path(), file.extension()));
    }

    @Override
    public Optional<WorkAssetFile> rawFile(WorkType workType, long workId, int page) {
        return switch (workType) {
            case ARTWORK -> artworkRawFile(workId, page);
            case NOVEL -> novelRawFile(workId, page);
        };
    }

    @Override
    public Optional<WorkAssetFile> coverThumbnail(WorkType workType, long workId, int page, int maximumEdge) throws IOException {
        if (workType != WorkType.ARTWORK) return Optional.empty();
        var file = artworkFileService.getThumbnailFile(workId, page, maximumEdge, true);
        return file == null ? Optional.empty() : Optional.of(new WorkAssetFile(page, file.path(), file.extension()));
    }

    @Override
    public boolean deleteLocalFiles(WorkType workType, long workId) {
        return deleteLocalFiles(workType, workId, () -> {});
    }

    /** 宿主删除编排在文件备份尚未释放时完成记录事务。 */
    public boolean deleteLocalFiles(WorkType workType, long workId, Runnable commitRecord) {
        try (var workFileLease = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(workType, workId)) {
            if (isReadOnly(workType, workId)) {
                commitRecord.run();
                return true;
            }
            return switch (workType) {
                case ARTWORK -> artworkFileLocator.deleteArtworkFiles(pixivDatabase.getArtwork(workId), commitRecord);
                case NOVEL -> deleteNovelFiles(workId, commitRecord);
            };
        }
    }

    // ── 插画侧 ─────────────────────────────────────────────────────────────────

    private Optional<LocalWorkAsset> findArtworkAsset(long workId) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(workId);
        if (artwork == null) {
            return Optional.empty();
        }
        String directoryPath = artworkFileLocator.resolveArtworkDirectory(artwork);
        int pageCount = Math.max(artwork.count(), 1);
        List<WorkAssetFile> files = new ArrayList<>();
        List<File> images = artworkFileLocator.resolveImageFiles(artwork, false);
        for (int page = 0; page < pageCount; page++) {
            File file = images.get(page);
            if (file == null) {
                continue;
            }
            files.add(new WorkAssetFile(page, file.toPath(), extensionOf(file.getName())));
        }
        return Optional.of(new LocalWorkAsset(
                WorkType.ARTWORK,
                workId,
                StringUtils.hasText(directoryPath) ? Paths.get(directoryPath) : null,
                pageCount,
                files));
    }

    private Optional<WorkAssetFile> artworkThumbnail(long workId, int page) throws IOException {
        ArtworkFileService.ThumbnailFile thumbnailFile = artworkFileService.getThumbnailFile(workId, page);
        if (thumbnailFile == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkAssetFile(page, thumbnailFile.path(), thumbnailFile.extension()));
    }

    private Optional<WorkAssetFile> artworkRawFile(long workId, int page) {
        File file = artworkFileService.getImageFile(workId, page);
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkAssetFile(page, file.toPath(), extensionOf(file.getName())));
    }

    // ── 小说侧 ─────────────────────────────────────────────────────────────────

    private Optional<LocalWorkAsset> findNovelAsset(long workId) {
        if (isReadOnly(WorkType.NOVEL, workId)) {
            List<WorkAssetFile> result = new ArrayList<>();
            List<Path> paths = externalFiles.files(WorkType.NOVEL, workId);
            for (int i = 0; i < paths.size(); i++) {
                Path file = paths.get(i);
                if (file != null) result.add(new WorkAssetFile(i, file, extensionOf(file.getFileName().toString())));
            }
            return Optional.of(new LocalWorkAsset(WorkType.NOVEL, workId, null, paths.size(), result));
        }
        NovelMetadataRow novel = novelMetadataRepository.getNovel(workId);
        if (novel == null) {
            return Optional.empty();
        }
        Path dir = exclusiveNovelDirectory(novel, false);
        List<WorkAssetFile> files = dir == null ? List.of() : enumerateNovelFiles(novel, dir);
        return Optional.of(new LocalWorkAsset(WorkType.NOVEL, workId, dir, files.size(), files));
    }

    /**
     * 枚举小说独占目录下的全部常规文件，按路径字典序排序保证跨 OS 可复现；
     * 页号是本次枚举快照内的临时序号（见接口 javadoc）。目录不可读时记日志并视为无文件。
     */
    private List<WorkAssetFile> enumerateNovelFiles(NovelMetadataRow novel, Path dir) {
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    // meta sidecar 是作品自身元数据、非可下载内容文件，排除出枚举（导出 zip 不含 *.meta.json）。
                    .filter(p -> !WorkSidecarFiles.isSidecarFile(p))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            List<WorkAssetFile> files = new ArrayList<>(paths.size());
            for (int page = 0; page < paths.size(); page++) {
                Path path = paths.get(page);
                files.add(new WorkAssetFile(page, path, extensionOf(path.getFileName().toString())));
            }
            return files;
        } catch (IOException e) {
            log.warn(logMessage("download.asset.log.novel-directory-unreadable", novel.novelId(), dir), e);
            return List.of();
        }
    }

    /** 小说缩略图 = 封面文件 {@code {存储基名}_thumb.{coverExt}}；page 参数无意义，返回页号恒为 0。 */
    private Optional<WorkAssetFile> novelCover(long workId) {
        if (isReadOnly(WorkType.NOVEL, workId)) return Optional.empty();
        NovelMetadataRow novel = novelMetadataRepository.getNovel(workId);
        if (novel == null || !StringUtils.hasText(novel.coverExt()) || !StringUtils.hasText(novel.folder())) {
            return Optional.empty();
        }
        Path file;
        try {
            file = Paths.get(novel.folder(), resolveStoredNovelBaseName(novel) + "_thumb." + novel.coverExt());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new WorkAssetFile(0, file, novel.coverExt()));
    }

    private Optional<WorkAssetFile> novelRawFile(long workId, int page) {
        return findNovelAsset(workId).flatMap(asset ->
                page >= 0 && page < asset.files().size()
                        ? Optional.of(asset.files().get(page))
                        : Optional.empty());
    }

    /**
     * 小说落盘文件的存储基名：按下载时使用的文件名模板与文件名作者名重放格式化
     * （{@code fileName} 为空回退默认模板），与小说下载链路的命名规则一致。
     */
    private String resolveStoredNovelBaseName(NovelMetadataRow novel) {
        String saved = novelMetadataRepository.getFileBaseName(novel.novelId());
        if (StringUtils.hasText(saved)) {
            requireFileName(saved);
            return saved;
        }
        String template = novel.fileName() == null
                ? PixivWorkFileNameFormatter.DEFAULT_TEMPLATE
                : pixivDatabase.getFileNameTemplate(novel.fileName());
        String authorName = novel.fileAuthorNameId() == null
                ? ""
                : pixivDatabase.getFileAuthorName(novel.fileAuthorNameId());
        if (authorName == null) authorName = "";
        int maxLength = pixivDatabase.getFileNameMaxLength(novel.novelId(), true);
        List<String> names = PixivWorkFileNameFormatter.formatAll(
                template, novel.novelId(), novel.title(), novel.authorId(), authorName,
                novel.time(), 1, novel.isAi(), novel.xRestrict(),
                maxLength > 0 ? maxLength : PixivWorkFileNameFormatter.MAX_BASENAME_LENGTH);
        return names.isEmpty() ? String.valueOf(novel.novelId()) : names.get(0);
    }

    /**
     * 暂存小说独占目录中的文件，在记录提交成功后仅移除空目录。
     * 删除或记录提交失败时尝试回滚，未恢复的备份继续保留。
     */
    private boolean deleteNovelFiles(long workId, Runnable commitRecord) {
        NovelMetadataRow record = novelMetadataRepository.getNovel(workId);
        if (record == null) {
            commitRecord.run();
            return true;
        }
        Path dir = exclusiveNovelDirectory(record, true);
        if (dir == null) {
            commitRecord.run();
            return true;
        }
        List<Path> files;
        try (var stream = Files.walk(dir)) {
            files = stream
                    .filter(path -> !path.equals(dir))
                    .filter(path -> !PlainFilePathGuard.isPlainDirectory(path))
                    .toList();
        } catch (IOException e) {
            log.warn(logMessage("novel.gallery.log.clean-directory-failed", record.novelId(), record.folder()));
            return false;
        }
        if (!stagedFileDeletion.deleteAtomically(files, commitRecord)) {
            return false;
        }
        removeEmptyDirectoryTree(dir, record);
        return true;
    }

    /** 移除已清空的小说独占目录壳（子目录 + 目录本身）；可再生，删失败仅记日志、不影响删除成败。 */
    private void removeEmptyDirectoryTree(Path dir, NovelMetadataRow record) {
        try (var stream = Files.walk(dir)) {
            stream.filter(PlainFilePathGuard::isPlainDirectory).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn(logMessage("novel.gallery.log.clean-directory-failed", record.novelId(), record.folder()));
                }
            });
        } catch (IOException e) {
            log.warn(logMessage("novel.gallery.log.clean-directory-failed", record.novelId(), record.folder()));
        }
    }

    /**
     * 解析小说独占目录并执行磁盘边界守卫（避免污染的 folder 把递归操作范围扩大到 root 之外、
     * 共享目录或 OS 根）：解析后的目录必须非空、可解析、是已存在目录、非 OS / 驱动盘根、
     * 且不等于配置的 {@code download.root-folder} 本身；同时目录名必须等于 {@code novel-{novelId}}
     * 才视为本小说独占目录。枚举链路遇到不满足项时返回 {@code null}；删除链路只有已确认不存在的目录
     * 视为「无可操作目录」，现存或无法判定安全的目录抛出 {@link UnsafeDeletionPathException}。
     *
     * @param logRefusals 删除链路传 {@code true}（守卫拒绝时记日志，polluted folder 行可由管理员
     *                    据此排查）；枚举链路传 {@code false}（与原导出路径的静默跳过一致）
     */
    private Path exclusiveNovelDirectory(NovelMetadataRow record, boolean logRefusals) {
        String folder = record.folder();
        if (folder == null || folder.isBlank()) {
            if (logRefusals) throw new UnsafeDeletionPathException(folder);
            return null;
        }
        Path dir;
        try {
            dir = Paths.get(folder).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            if (logRefusals) {
                log.warn(logMessage("novel.gallery.log.directory-invalid", record.novelId(), folder));
                throw new UnsafeDeletionPathException(folder);
            }
            return null;
        }
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!PlainFilePathGuard.isPlainDirectory(dir)) {
            if (logRefusals) {
                throw new UnsafeDeletionPathException(dir);
            }
            return null;
        }
        if (dir.getNameCount() < 1 || dir.equals(dir.getRoot())) {
            if (logRefusals) {
                log.warn(logMessage("novel.gallery.log.directory-root-refused", record.novelId(), dir));
                throw new UnsafeDeletionPathException(dir);
            }
            return null;
        }
        try {
            Path downloadRoot = Paths.get(downloadConfig.getRootFolder()).toAbsolutePath().normalize();
            if (dir.equals(downloadRoot)) {
                if (logRefusals) {
                    log.warn(logMessage("novel.gallery.log.directory-root-folder-refused",
                            record.novelId(), dir));
                    throw new UnsafeDeletionPathException(dir);
                }
                return null;
            }
        } catch (InvalidPathException ignored) {
            // 解析 download.root-folder 失败仅意味着无法做 root 自身比对，目录名守卫仍然生效。
        }
        Path name = dir.getFileName();
        String expectedName = "novel-" + record.novelId();
        if (name == null || !expectedName.equals(name.toString())) {
            if (logRefusals) {
                log.warn(logMessage("novel.gallery.log.directory-not-exclusive",
                        record.novelId(), dir, expectedName));
                throw new UnsafeDeletionPathException(dir);
            }
            return null;
        }
        return dir;
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private static String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 && dotIndex < fileName.length() - 1
                ? fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT)
                : "jpg";
    }
}
