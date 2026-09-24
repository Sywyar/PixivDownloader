package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("动图临时路径的作品隔离")
class UgoiraTempPathsTest {

    private static final Path SHARED = Path.of("E:", "Pixiv", "(122036969)YaeMiyabi");

    @Test
    @DisplayName("同一共享目录下，不同作品拿到互不相同的临时 zip 与解帧目录")
    void differentArtworksDoNotShareTempPaths() {
        // 这是共享目录（download.artwork-folder-template）下的核心不变量：
        // 两个作品并发转换若共用 _ugoira_frames.zip / _frames_tmp，
        // 会互相覆盖，并在各自开头的 cleanup 里把对方正在用的解帧目录删掉。
        Path zipA = UgoiraTempPaths.zip(SHARED, 111L);
        Path zipB = UgoiraTempPaths.zip(SHARED, 222L);
        Path framesA = UgoiraTempPaths.framesDir(SHARED, 111L);
        Path framesB = UgoiraTempPaths.framesDir(SHARED, 222L);

        assertThat(zipA).isNotEqualTo(zipB);
        assertThat(framesA).isNotEqualTo(framesB);
        assertThat(zipA).isNotEqualTo(framesA);
        assertThat(zipB).isNotEqualTo(framesB);
    }

    @Test
    @DisplayName("临时产物落在作品目录内，且名字带上作品 ID")
    void tempArtifactsStayInArtworkDirectoryAndCarryArtworkId() {
        Path zip = UgoiraTempPaths.zip(SHARED, 12345L);
        Path frames = UgoiraTempPaths.framesDir(SHARED, 12345L);

        assertThat(zip.getParent()).isEqualTo(SHARED);
        assertThat(frames.getParent()).isEqualTo(SHARED);
        assertThat(zip.getFileName().toString()).contains("12345");
        assertThat(frames.getFileName().toString()).contains("12345");
    }

    @Test
    @DisplayName("artworkId 缺失时退化为固定 token，不抛异常")
    void nullArtworkIdDegradesGracefully() {
        assertThat(UgoiraTempPaths.zip(SHARED, null).getFileName().toString())
                .isEqualTo(UgoiraTempPaths.zip(SHARED, null).getFileName().toString());
        assertThat(UgoiraTempPaths.zip(SHARED, null)).isNotEqualTo(UgoiraTempPaths.zip(SHARED, 1L));
    }

    @Test
    @DisplayName("长度占用哨兵与实际临时路径同源，不会各写一份而漏改")
    void pathSentinelsMatchActualTempPaths() {
        Long artworkId = 987L;
        List<String> sentinels = UgoiraTempPaths.pathSentinels(artworkId);

        String zipPart = UgoiraTempPaths.zip(SHARED, artworkId).getFileName() + ".part";
        String progressLog = UgoiraTempPaths.framesDir(SHARED, artworkId).getFileName()
                + "/ffmpeg-progress.log";

        assertThat(sentinels).containsExactly(zipPart, progressLog);
    }
}
