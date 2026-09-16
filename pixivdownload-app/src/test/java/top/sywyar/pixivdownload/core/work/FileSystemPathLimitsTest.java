package top.sywyar.pixivdownload.core.work;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("下载目标卷路径能力")
class FileSystemPathLimitsTest {
    @TempDir Path root;

    @Test
    @DisplayName("只读探测识别组成部分超限且不创建文件")
    void detectsTooLongComponentWithoutWriting() throws Exception {
        var supported = FileSystemPathLimits.support(root.resolve("not-created"));
        assertThat(supported.test(root.resolve("not-created/123_p0.jpg"))).isTrue();
        assertThat(supported.test(root.resolve("not-created").resolve("x".repeat(1024)))).isFalse();
        try (var children = Files.list(root)) {
            assertThat(children.count()).isZero();
        }
    }

    @Test
    @DisplayName("现代 NIO 可写超过 260 字符的路径，不按旧 Windows 上限拦截")
    void permitsAndWritesLongPaths() throws Exception {
        Path folder = root;
        while (folder.toString().length() < 280) folder = folder.resolve("directory-123456789");
        Path target = folder.resolve("作品_p0.jpg");
        assertThat(FileSystemPathLimits.support(folder).test(target)).isTrue();
        Files.createDirectories(folder);
        Files.writeString(target, "path support");
        assertThat(Files.readString(target)).isEqualTo("path support");
    }
}
