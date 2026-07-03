package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ArtworkFileService 单元测试")
class ArtworkFileServiceTest {

    // ========== findFileByName ==========

    @Nested
    @DisplayName("findFileByName")
    class FindFileByNameTests {

        @Test
        @DisplayName("目录不存在时应返回 null")
        void shouldReturnNullWhenDirectoryNotExists() {
            File result = ArtworkFileService.findFileByName("/non/existent/path", "test");
            assertThat(result).isNull();
        }
    }
}
