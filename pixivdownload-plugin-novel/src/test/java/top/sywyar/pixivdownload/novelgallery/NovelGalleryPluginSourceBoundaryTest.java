package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("novel-gallery 源码边界：不得硬编码 sidecar 文件名实现细节")
class NovelGalleryPluginSourceBoundaryTest {

    private static final Path SOURCE_ROOT =
            Path.of("src", "main", "java", "top", "sywyar", "pixivdownload", "novelgallery");

    private static final List<String> FORBIDDEN_TOKENS = List.of(
            ".meta.json",
            "SIDECAR_SUFFIX",
            "WorkSidecarStore"
    );

    @Test
    @DisplayName("novel-gallery 源码不得出现 sidecar 文件名实现细节（.meta.json / SIDECAR_SUFFIX / WorkSidecarStore）")
    void novelGallerySourcesMustNotMentionSidecarImplementationDetails() {
        List<Path> sources = collectJavaFiles(SOURCE_ROOT);

        assertThat(sources)
                .as("novel-gallery source 扫描清单不应为空")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = readLines(source);
            for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
                String line = lines.get(lineNo);
                for (String token : FORBIDDEN_TOKENS) {
                    if (line.contains(token)) {
                        violations.add(source + ":" + (lineNo + 1)
                                + "  命中 \"" + token + "\"  ->  " + line.trim());
                    }
                }
            }
        }

        assertThat(violations)
                .as("novel-gallery 不得硬编码 sidecar 文件名实现细节：sidecar 没有对插件发布读取面，"
                        + "普通作品文件枚举 / 读取应经 WorkAssetService。"
                        + "\n命中清单：\n%s", String.join("\n", violations))
                .isEmpty();
    }

    private static List<Path> collectJavaFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历 novel-gallery 源码目录失败：" + dir, e);
        }
    }

    private static List<String> readLines(Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 novel-gallery 源码失败：" + source, e);
        }
    }
}
