package top.sywyar.pixivdownload.download;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.plugin.api.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.WorkType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link WorkAssetService} 的核心实现：代理 {@link DownloadService}（缩略图缓存 / 原图定位）
 * 与 {@link ArtworkFileLocator}（文件层删除），解析与删除语义同直接调用两者完全一致。
 *
 * <p>{@link WorkType#NOVEL} 尚未接入：小说本地文件仍由 {@code NovelGalleryService} 自管，
 * 待小说画廊改走核心接口时在此接入并翻转 {@code LocalWorkAssetServiceTest} 的契约单测。
 */
@Component
@RequiredArgsConstructor
public class LocalWorkAssetService implements WorkAssetService {

    private final DownloadService downloadService;
    private final ArtworkFileLocator artworkFileLocator;
    private final PixivDatabase pixivDatabase;

    @Override
    public Optional<LocalWorkAsset> findAsset(WorkType workType, long workId) {
        requireSupported(workType);
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
                workType,
                workId,
                StringUtils.hasText(directoryPath) ? Paths.get(directoryPath) : null,
                pageCount,
                files));
    }

    @Override
    public Optional<WorkAssetFile> thumbnail(WorkType workType, long workId, int page) throws IOException {
        requireSupported(workType);
        DownloadService.ThumbnailFile thumbnailFile = downloadService.getThumbnailFile(workId, page);
        if (thumbnailFile == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkAssetFile(page, thumbnailFile.path(), thumbnailFile.extension()));
    }

    @Override
    public Optional<WorkAssetFile> rawFile(WorkType workType, long workId, int page) {
        requireSupported(workType);
        File file = downloadService.getImageFile(workId, page);
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkAssetFile(page, file.toPath(), extensionOf(file.getName())));
    }

    @Override
    public boolean deleteLocalFiles(WorkType workType, long workId) {
        requireSupported(workType);
        return artworkFileLocator.deleteArtworkFiles(pixivDatabase.getArtwork(workId));
    }

    private static void requireSupported(WorkType workType) {
        if (workType != WorkType.ARTWORK) {
            throw new UnsupportedOperationException(
                    "WorkAssetService 尚未接入 " + workType + "：小说本地文件仍由小说画廊侧自管");
        }
    }

    private static String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 && dotIndex < fileName.length() - 1
                ? fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT)
                : "jpg";
    }
}
