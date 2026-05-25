package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CollectionIconService 集成测试")
class CollectionIconServiceTest {

    /**
     * 测试用极低概率冲突的 ID 占位。
     */
    private static final long ID_PNG_BY_NAME = 9_900_001L;
    private static final long ID_JPEG_BY_NAME = 9_900_002L;
    private static final long ID_PNG_BY_MAGIC = 9_900_003L;
    private static final long ID_JPG_BY_MAGIC = 9_900_004L;
    private static final long ID_WEBP_BY_MAGIC = 9_900_005L;
    private static final long ID_OVERWRITE = 9_900_006L;
    private static final long ID_INVALID = 9_900_007L;

    @TempDir
    Path tempDir;

    private Path collectionIconsDir;
    private CollectionIconService iconService;

    @BeforeEach
    void setUp() throws IOException {
        Path dataDir = tempDir.resolve("data");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, dataDir.toString());
        collectionIconsDir = dataDir.resolve(RuntimeFiles.COLLECTION_ICONS_DIR);
        iconService = new CollectionIconService(TestI18nBeans.appMessages());
        iconService.init();
    }

    @AfterEach
    void tearDown() {
        for (long id : new long[]{ID_PNG_BY_NAME, ID_JPEG_BY_NAME, ID_PNG_BY_MAGIC,
                ID_JPG_BY_MAGIC, ID_WEBP_BY_MAGIC, ID_OVERWRITE, ID_INVALID}) {
            iconService.deleteAll(id);
        }
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    @Test
    @DisplayName("通过文件名扩展名识别 png")
    void shouldDetectPngByFilename() throws IOException {
        String ext = iconService.saveIcon(ID_PNG_BY_NAME, "icon.PNG", new byte[]{0, 0, 0, 0});

        assertThat(ext).isEqualTo("png");
        assertThat(iconService.findExistingIcon(ID_PNG_BY_NAME, "png")).isNotNull();
    }

    @Test
    @DisplayName("文件名 .jpeg 应被归一化为 jpg")
    void shouldNormalizeJpegToJpg() throws IOException {
        String ext = iconService.saveIcon(ID_JPEG_BY_NAME, "icon.jpeg", new byte[]{0, 0, 0, 0});

        assertThat(ext).isEqualTo("jpg");
    }

    @Test
    @DisplayName("无文件名时应通过 magic-number 识别 png")
    void shouldDetectPngByMagicNumber() throws IOException {
        byte[] png = new byte[16];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';

        String ext = iconService.saveIcon(ID_PNG_BY_MAGIC, null, png);

        assertThat(ext).isEqualTo("png");
    }

    @Test
    @DisplayName("无文件名时应通过 magic-number 识别 jpg")
    void shouldDetectJpgByMagicNumber() throws IOException {
        byte[] jpg = new byte[16];
        jpg[0] = (byte) 0xFF;
        jpg[1] = (byte) 0xD8;

        String ext = iconService.saveIcon(ID_JPG_BY_MAGIC, null, jpg);

        assertThat(ext).isEqualTo("jpg");
    }

    @Test
    @DisplayName("无文件名时应通过 RIFF/WEBP magic-number 识别 webp")
    void shouldDetectWebpByMagicNumber() throws IOException {
        byte[] webp = new byte[16];
        webp[0] = 'R';
        webp[1] = 'I';
        webp[2] = 'F';
        webp[3] = 'F';
        webp[8] = 'W';
        webp[9] = 'E';
        webp[10] = 'B';
        webp[11] = 'P';

        String ext = iconService.saveIcon(ID_WEBP_BY_MAGIC, null, webp);

        assertThat(ext).isEqualTo("webp");
    }

    @Test
    @DisplayName("既无可识别文件名也无可识别 magic-number 应抛 LocalizedException")
    void shouldRejectUnknownFormat() {
        assertThatThrownBy(() -> iconService.saveIcon(
                ID_INVALID, "icon.bmp", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                        .isEqualTo("collection.icon.format.unsupported"));
    }

    @Test
    @DisplayName("再次保存图标时应清理旧扩展名的文件")
    void shouldOverwritePreviousExtensionWhenSavingAgain() throws IOException {
        // 先放一张 png
        iconService.saveIcon(ID_OVERWRITE, "icon.png", new byte[]{0, 0, 0, 0});
        Path pngPath = collectionIconsDir.resolve(ID_OVERWRITE + ".png");
        assertThat(Files.exists(pngPath)).isTrue();

        // 再换成 webp
        byte[] webp = new byte[16];
        webp[0] = 'R';
        webp[1] = 'I';
        webp[2] = 'F';
        webp[3] = 'F';
        webp[8] = 'W';
        webp[9] = 'E';
        webp[10] = 'B';
        webp[11] = 'P';
        iconService.saveIcon(ID_OVERWRITE, "icon.webp", webp);

        assertThat(Files.exists(pngPath)).as("旧 png 应已被清理").isFalse();
        assertThat(Files.exists(collectionIconsDir.resolve(ID_OVERWRITE + ".webp"))).isTrue();
    }

    @Test
    @DisplayName("contentType 应按扩展名输出 MIME")
    void shouldMapContentTypeByExtension() {
        assertThat(iconService.contentType("png")).isEqualTo("image/png");
        assertThat(iconService.contentType("webp")).isEqualTo("image/webp");
        assertThat(iconService.contentType("jpg")).isEqualTo("image/jpeg");
        assertThat(iconService.contentType("anything-else")).isEqualTo("image/jpeg");
    }
}
