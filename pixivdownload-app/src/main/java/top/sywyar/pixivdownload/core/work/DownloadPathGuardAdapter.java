package top.sywyar.pixivdownload.core.work;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.SafePathSegment;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.DownloadPathRejectedException;

import java.nio.file.Path;

/**
 * 将下载路径安全端口适配到宿主路径校验与本地化错误流。
 */
@Component
public class DownloadPathGuardAdapter implements DownloadPathGuard {

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
