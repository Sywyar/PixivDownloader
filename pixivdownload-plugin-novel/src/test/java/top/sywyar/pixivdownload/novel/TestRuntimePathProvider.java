package top.sywyar.pixivdownload.novel;

import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 测试用运行期路径提供者：把所有路径隔离到单个临时目录。 */
public final class TestRuntimePathProvider implements RuntimePathProvider {

    private final Path root;

    public TestRuntimePathProvider(Path root) {
        this.root = root;
    }

    @Override
    public Path configFile(String extension) {
        return directory(root.resolve("config").resolve("plugins"))
                .resolve("novel." + extension);
    }

    @Override
    public Path dataDirectory() {
        return directory(root.resolve("data").resolve("novel"));
    }

    @Override
    public Path stateDirectory() {
        return directory(root.resolve("state").resolve("novel"));
    }

    /** 返回花名册参考音目录但不创建，便于断言删除结果。 */
    public Path narrationVoicePath(long castId) {
        return root.resolve("data").resolve("novel").resolve("narration-voice").resolve(Long.toString(castId));
    }

    private static Path directory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
