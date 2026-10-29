package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;
import java.nio.file.Files;
import java.nio.file.Path;

final class HistoryTestDirectory implements TempDirFactory {
    @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws Exception {
        // 与工作目录同卷，确保 Windows 也能覆盖默认相对缓存路径。
        return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "history-test-");
    }
}
