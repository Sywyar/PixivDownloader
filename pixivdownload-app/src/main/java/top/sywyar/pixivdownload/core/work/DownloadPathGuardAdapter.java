package top.sywyar.pixivdownload.core.work;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.SafePathSegment;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.DownloadPathRejectedException;
import top.sywyar.pixivdownload.core.work.service.DownloadPathLimits;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/**
 * 将下载路径安全端口适配到宿主路径校验与本地化错误流。
 */
@Component
public class DownloadPathGuardAdapter implements DownloadPathGuard {
    private final DownloadDirectories directories;
    private final ArtworkFileLocator artworks;

    public DownloadPathGuardAdapter(DownloadDirectories directories, ArtworkFileLocator artworks) {
        this.artworks = artworks;
        this.directories = directories;
    }

    @Override
    public Closeable reserveOutputs(long artworkId, Collection<Path> files) throws IOException {
        return artworks.reserveOutputs(artworkId, files);
    }

    @Override
    public void prepareDirectory(top.sywyar.pixivdownload.core.work.model.WorkType type, long id, Path directory)
            throws java.io.IOException {
        directories.prepare(type, id, directory);
    }

    @Override
    public boolean prepareDirectoryIf(top.sywyar.pixivdownload.core.work.model.WorkType type, long id, Path directory,
                                      java.util.function.BooleanSupplier registerLayout) throws java.io.IOException {
        return directories.prepareIf(type, id, directory, registerLayout);
    }

    @Override
    public DownloadPathLimits limits(Path directory) {
        return FileSystemPathLimits.read(directory);
    }

    @Override
    public java.util.function.Predicate<Path> pathSupport(Path directory) {
        return FileSystemPathLimits.support(directory);
    }

    @Override
    public String requireSafeDirectoryName(String value) {
        try {
            return SafePathSegment.requireSafeDirectoryName(value);
        } catch (top.sywyar.pixivdownload.i18n.LocalizedException rejected) {
            throw new DownloadPathRejectedException();
        }
    }

    @Override
    public void requireWithinRoot(Path root, Path candidate) {
        Path normalizedRoot = root == null ? null : root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate == null ? null : candidate.toAbsolutePath().normalize();
        if (normalizedRoot == null || normalizedCandidate == null
                || !normalizedCandidate.startsWith(normalizedRoot)) {
            throw new DownloadPathRejectedException();
        }
    }
}
