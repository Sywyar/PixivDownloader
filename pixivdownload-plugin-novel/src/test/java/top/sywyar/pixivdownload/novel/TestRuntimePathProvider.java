package top.sywyar.pixivdownload.novel;

import top.sywyar.pixivdownload.config.RuntimePathProvider;

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
    public Path resolvePluginConfigPath(String pluginId, String extension) {
        return directory(root.resolve("config").resolve("plugins"))
                .resolve(pluginId + "." + extension);
    }

    @Override
    public Path resolvePluginDataDirectory(String pluginId) {
        return directory(root.resolve("data").resolve(pluginId));
    }

    @Override
    public Path resolvePluginStateDirectory(String pluginId) {
        return directory(root.resolve("state").resolve(pluginId));
    }

    @Override
    public Path resolveBatchStatePath() {
        return directory(root.resolve("state")).resolve("batch_state.json");
    }

    @Override
    public Path narrationVoiceDirectory(long castId) {
        return directory(narrationVoicePath(castId));
    }

    @Override
    public Path narrationVoiceFile(long castId, int characterId, String extension) {
        return narrationVoiceDirectory(castId).resolve(characterId + "." + extension);
    }

    @Override
    public Path resolveEdgeTtsVersionPath() {
        return directory(root.resolve("data").resolve("tts")).resolve("chromium-version.txt");
    }

    /** 返回花名册参考音目录但不创建，便于断言删除结果。 */
    public Path narrationVoicePath(long castId) {
        return root.resolve("data").resolve("narration-voice").resolve(Long.toString(castId));
    }

    private static Path directory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
