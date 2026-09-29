package top.sywyar.pixivdownload.download.state;

import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 下载页面偏好独立保存，避免被旧标签页的整份工作台自动保存覆盖。 */
public final class DownloadPagePreference {
    public static final String CLASSIC = "pixiv-batch.html";
    public static final String ALTERNATE = "pixiv-batch-alt.html";
    private final Path file;

    public DownloadPagePreference(RuntimePathProvider paths) {
        file = paths.stateDirectory().resolve("download_page.txt");
    }

    public static boolean supports(String page) {
        return CLASSIC.equals(page) || ALTERNATE.equals(page);
    }

    public synchronized String currentPage() throws IOException {
        try (var input = Files.newInputStream(file)) {
            // 只接受完整的页面 token；多读一字节以识别超长或尾随内容。
            String value = new String(input.readNBytes(ALTERNATE.length() + 1), StandardCharsets.UTF_8);
            return supports(value) ? value : CLASSIC;
        } catch (NoSuchFileException missing) {
            return CLASSIC;
        }
    }

    public synchronized void save(String page) throws IOException {
        if (!supports(page)) throw new IllegalArgumentException("Unsupported download page");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "download-page-", ".tmp");
        try {
            Files.writeString(temporary, page, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
