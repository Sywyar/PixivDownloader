package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

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
    @DisplayName("合法 PNG 应真实解码并重编码保存为 png")
    void shouldDetectPngByFilename() throws IOException {
        String ext = iconService.saveIcon(ID_PNG_BY_NAME, pngBytes(32, 24));

        assertThat(ext).isEqualTo("png");
        assertThat(iconService.findExistingIcon(ID_PNG_BY_NAME, "png")).isNotNull();
        assertThat(ImageIO.read(collectionIconsDir.resolve(ID_PNG_BY_NAME + ".png").toFile()).getWidth())
                .isEqualTo(32);
    }

    @Test
    @DisplayName("合法 JPEG 应真实解码并重编码保存为 png")
    void shouldNormalizeJpegToJpg() throws IOException {
        String ext = iconService.saveIcon(ID_JPEG_BY_NAME, jpegBytes(16, 16));

        assertThat(ext).isEqualTo("png");
        assertThat(iconService.findExistingIcon(ID_JPEG_BY_NAME, "png")).isNotNull();
    }

    @Test
    @DisplayName("无文件名时应通过真实内容识别 PNG")
    void shouldDetectPngByMagicNumber() throws IOException {
        String ext = iconService.saveIcon(ID_PNG_BY_MAGIC, pngBytes(12, 12));

        assertThat(ext).isEqualTo("png");
    }

    @Test
    @DisplayName("无文件名时应通过真实内容识别 JPEG 并重编码为 PNG")
    void shouldDetectJpgByMagicNumber() throws IOException {
        String ext = iconService.saveIcon(ID_JPG_BY_MAGIC, jpegBytes(12, 12));

        assertThat(ext).isEqualTo("png");
    }

    @Test
    @DisplayName("仅伪造 RIFF/WEBP 文件头但无法解码应拒绝")
    void shouldRejectFakeWebpMagicNumber() {
        byte[] webp = new byte[16];
        webp[0] = 'R';
        webp[1] = 'I';
        webp[2] = 'F';
        webp[3] = 'F';
        webp[8] = 'W';
        webp[9] = 'E';
        webp[10] = 'B';
        webp[11] = 'P';

        assertThatThrownBy(() -> iconService.saveIcon(ID_WEBP_BY_MAGIC, webp))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                        .isEqualTo("collection.icon.format.unsupported"));
    }

    @Test
    @DisplayName("既无可识别文件名也无可识别 magic-number 应抛 LocalizedException")
    void shouldRejectUnknownFormat() {
        assertThatThrownBy(() -> iconService.saveIcon(
                ID_INVALID, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                        .isEqualTo("collection.icon.format.unsupported"));
    }

    @Test
    @DisplayName("再次保存图标时应清理旧扩展名的文件")
    void shouldOverwritePreviousExtensionWhenSavingAgain() throws IOException {
        iconService.replaceAtomic(ID_OVERWRITE, "jpg", jpegBytes(8, 8));
        Path jpgPath = collectionIconsDir.resolve(ID_OVERWRITE + ".jpg");
        assertThat(Files.exists(jpgPath)).isTrue();

        iconService.saveIcon(ID_OVERWRITE, pngBytes(10, 10));

        assertThat(Files.exists(jpgPath)).as("旧 jpg 应已被清理").isFalse();
        assertThat(Files.exists(collectionIconsDir.resolve(ID_OVERWRITE + ".png"))).isTrue();
    }

    @Test
    @DisplayName("SVG 内容改名为 PNG 应拒绝")
    void shouldRejectSvgRenamedAsPng() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> iconService.saveIcon(ID_INVALID, svg))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                        .isEqualTo("collection.icon.format.unsupported"));
    }

    @Test
    @DisplayName("PNG 文件头伪造但无法解码应拒绝")
    void shouldRejectPngMagicWithoutDecodableImage() {
        byte[] png = new byte[16];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';

        assertThatThrownBy(() -> iconService.saveIcon(ID_INVALID, png))
                .isInstanceOf(LocalizedException.class)
                .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                        .isEqualTo("collection.icon.format.unsupported"));
    }

    @Test
    @DisplayName("contentType 应按扩展名输出 MIME")
    void shouldMapContentTypeByExtension() {
        assertThat(iconService.contentType("png")).isEqualTo("image/png");
        assertThat(iconService.contentType("webp")).isEqualTo("image/webp");
        assertThat(iconService.contentType("jpg")).isEqualTo("image/jpeg");
        assertThat(iconService.contentType("anything-else")).isEqualTo("image/jpeg");
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        return imageBytes(width, height, "png", BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] jpegBytes(int width, int height) throws IOException {
        return imageBytes(width, height, "jpg", BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] imageBytes(int width, int height, String format, int imageType) throws IOException {
        BufferedImage image = new BufferedImage(width, height, imageType);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x336699));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
