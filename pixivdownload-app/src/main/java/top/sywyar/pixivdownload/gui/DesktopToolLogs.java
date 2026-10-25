package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** 只解析受管 HTML 日志的文件名，不创建日志会话或调用系统壳。 */
final class DesktopToolLogs {
    private final Path directory;

    DesktopToolLogs(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    Optional<Path> find(String fileName) throws IOException {
        if (!validFileName(fileName)) throw new IllegalArgumentException("Invalid tool log file name");
        Path file = directory.resolve(fileName);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        PlainFilePathGuard.requirePlainRegularFile(file);
        return Optional.of(file);
    }

    static boolean validFileName(String name) {
        return name != null && name.length() <= 255 && name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*\\.html");
    }
}
