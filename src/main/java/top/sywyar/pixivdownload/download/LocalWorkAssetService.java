package top.sywyar.pixivdownload.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.plugin.api.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.WorkType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link WorkAssetService} 的核心实现。插画侧代理 {@link DownloadService}（缩略图缓存 /
 * 原图定位）与 {@link ArtworkFileLocator}（文件层删除），解析与删除语义同直接调用两者
 * 完全一致。小说侧自管 {@code novel-{id}} 独占目录（守卫 / 递归删除逻辑自小说画廊服务
 * 下沉，逐字保留）：{@code findAsset} 枚举目录下全部常规文件（页号 = 枚举序号），
 * 缩略图恒解析封面 {@code {存储基名}_thumb.{coverExt}}，语义详见接口 javadoc。
 *
 * <p>过渡期本类对 novel.db 包的 import 待小说侧仓库收编进核心数据层后消除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalWorkAssetService implements WorkAssetService {

    private final DownloadService downloadService;
    private final ArtworkFileLocator artworkFileLocator;
    private final PixivDatabase pixivDatabase;
    private final NovelDatabase novelDatabase;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;

    @Override
    public Optional<LocalWorkAsset> findAsset(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> findArtworkAsset(workId);
            case NOVEL -> findNovelAsset(workId);
        };
    }

    @Override
    public Optional<WorkAssetFile> thumbnail(WorkType workType, long workId, int page) throws IOException {
        return switch (workType) {
            case ARTWORK -> artworkThumbnail(workId, page);
            case NOVEL -> novelCover(workId);
        };
    }

    @Override
    public Optional<WorkAssetFile> rawFile(WorkType workType, long workId, int page) {
        return switch (workType) {
            case ARTWORK -> artworkRawFile(workId, page);
            case NOVEL -> novelRawFile(workId, page);
        };
    }

    @Override
    public boolean deleteLocalFiles(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> artworkFileLocator.deleteArtworkFiles(pixivDatabase.getArtwork(workId));
            case NOVEL -> deleteNovelFiles(workId);
        };
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
        for (int page = 0; page < pageCount; page++) {
            File file = artworkFileLocator.resolveImageFile(artwork, page);
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
        DownloadService.ThumbnailFile thumbnailFile = downloadService.getThumbnailFile(workId, page);
        if (thumbnailFile == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkAssetFile(page, thumbnailFile.path(), thumbnailFile.extension()));
    }

    private Optional<WorkAssetFile> artworkRawFile(long workId, int page) {
        File file = downloadService.getImageFile(workId, page);
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkAssetFile(page, file.toPath(), extensionOf(file.getName())));
    }

    // ── 小说侧 ─────────────────────────────────────────────────────────────────

    private Optional<LocalWorkAsset> findNovelAsset(long workId) {
        NovelRecord novel = novelDatabase.getNovel(workId);
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
    private List<WorkAssetFile> enumerateNovelFiles(NovelRecord novel, Path dir) {
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream
                    .filter(Files::isRegularFile)
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
        NovelRecord novel = novelDatabase.getNovel(workId);
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
    private String resolveStoredNovelBaseName(NovelRecord novel) {
        String template = novel.fileName() == null
                ? ArtworkFileNameFormatter.DEFAULT_TEMPLATE
                : pixivDatabase.getFileNameTemplate(novel.fileName());
        String authorName = novel.fileAuthorNameId() == null
                ? ""
                : pixivDatabase.getFileAuthorName(novel.fileAuthorNameId());
        if (authorName == null) authorName = "";
        List<String> names = ArtworkFileNameFormatter.formatAll(
                template, novel.novelId(), novel.title(), novel.authorId(), authorName,
                novel.time(), 1, novel.isAi(), novel.xRestrict());
        return names.isEmpty() ? String.valueOf(novel.novelId()) : names.get(0);
    }

    /**
     * 删除小说磁盘文件：每本小说独占 {@code {rootFolder}/novel-{novelId}/} 目录（小说无重定位语义），
     * 因此目录名必须匹配 {@code novel-{novelId}} 才会被递归删除。守卫与递归删除逻辑自小说画廊
     * 服务逐字下沉，无下载记录视为「无事可做」（{@code true}）。
     *
     * @return 文件层清理结果：{@code true} 表示所有尝试的删除都成功（或没有可删的文件 / 被边界守卫跳过），
     *         调用方可继续删 DB 行；{@code false} 表示有文件因锁定 / 权限不足等原因删除失败，
     *         调用方必须中止 DB 清理。
     */
    private boolean deleteNovelFiles(long workId) {
        NovelRecord record = novelDatabase.getNovel(workId);
        if (record == null) {
            return true;
        }
        Path dir = exclusiveNovelDirectory(record, true);
        if (dir == null) {
            return true;
        }
        boolean[] allDeleted = {true};
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn(logMessage("novel.gallery.log.delete-file-failed", p));
                    allDeleted[0] = false;
                }
            });
        } catch (IOException e) {
            log.warn(logMessage("novel.gallery.log.clean-directory-failed", record.novelId(), record.folder()));
            return false;
        }
        return allDeleted[0];
    }

    /**
     * 解析小说独占目录并执行磁盘边界守卫（避免污染的 folder 把递归操作范围扩大到 root 之外、
     * 共享目录或 OS 根）：解析后的目录必须非空、可解析、是已存在目录、非 OS / 驱动盘根、
     * 且不等于配置的 {@code download.root-folder} 本身；同时目录名必须等于 {@code novel-{novelId}}
     * 才视为本小说独占目录。任何一条不满足都返回 {@code null}（视为「无可操作目录」）。
     *
     * @param logRefusals 删除链路传 {@code true}（守卫拒绝时记日志，polluted folder 行可由管理员
     *                    据此排查）；枚举链路传 {@code false}（与原导出路径的静默跳过一致）
     */
    private Path exclusiveNovelDirectory(NovelRecord record, boolean logRefusals) {
        String folder = record.folder();
        if (folder == null || folder.isBlank()) {
            return null;
        }
        Path dir;
        try {
            dir = Paths.get(folder).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            if (logRefusals) {
                log.warn(logMessage("novel.gallery.log.directory-invalid", record.novelId(), folder));
            }
            return null;
        }
        if (!Files.isDirectory(dir)) {
            return null;
        }
        if (dir.getNameCount() < 1 || dir.equals(dir.getRoot())) {
            if (logRefusals) {
                log.warn(logMessage("novel.gallery.log.directory-root-refused", record.novelId(), dir));
            }
            return null;
        }
        try {
            Path downloadRoot = Paths.get(downloadConfig.getRootFolder()).toAbsolutePath().normalize();
            if (dir.equals(downloadRoot)) {
                if (logRefusals) {
                    log.warn(logMessage("novel.gallery.log.directory-root-folder-refused",
                            record.novelId(), dir));
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
