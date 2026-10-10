package top.sywyar.pixivdownload.gui.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("轻量 AWT 启动画面")
class StartupSplashTest {
    @Test
    @DisplayName("图标随应用资源交付，具有足够的高 DPI 分辨率")
    void iconIsAvailableOnTheApplicationClasspath() throws Exception {
        try (var input = StartupSplash.class.getResourceAsStream("/bootstrap/icon.png")) {
            assertThat(input).isNotNull();
            var image = ImageIO.read(input);
            assertThat(image.getWidth()).isGreaterThanOrEqualTo(416);
            assertThat(image.getHeight()).isEqualTo(image.getWidth());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "zh-CN", "zh-Hant", "ja-JP", "ko-KR"})
    @DisplayName("不同语言与放大文字在窄低空间换行，状态不与标题重叠")
    void localizedLayoutPreservesAllText(String languageTag) {
        Locale locale = Locale.forLanguageTag(languageTag);
        String text = MessageBundles.get(locale, "gui.launcher.splash.plugins");
        String family = StartupSplashView.fontFamily(AppInfo.NAME + text, locale);
        for (Dimension space : List.of(new Dimension(440, 280), new Dimension(260, 180))) {
            for (double scale : List.of(1d, 1.5d, 2.25d)) {
                var layout = StartupSplashView.layout(space, scale, family, AppInfo.NAME, text);
                assertThat(layout.width()).isLessThanOrEqualTo(space.width);
                assertThat(layout.statusTop()).isGreaterThan(layout.titleTop());
                assertThat(layout.titles().stream().mapToInt(line -> line.getCharacterCount()).sum()).isEqualTo(AppInfo.NAME.length());
                assertThat(layout.statuses().stream().mapToInt(line -> line.getCharacterCount()).sum()).isEqualTo(text.length());
                assertThat(layout.titles()).allSatisfy(line ->
                        assertThat(line.getAdvance()).isLessThanOrEqualTo(layout.width() - 2 * layout.padding() + 1f));
                assertThat(layout.statuses()).allSatisfy(line ->
                        assertThat(line.getAdvance()).isLessThanOrEqualTo(layout.width() - 2 * layout.padding() - 27f));
            }
        }
    }

    @Test
    @DisplayName("深浅与高对比度色板均可绘制，减少动态效果时帧内容不变")
    void appearanceAndMotionAffectRenderedPixels() {
        for (boolean dark : List.of(false, true)) {
            for (boolean contrast : List.of(false, true)) {
                var still = new StartupAppearance(dark, contrast, true, 1);
                assertThat(pixels(render(still, 0, 1))).isEqualTo(pixels(render(still, 5, 1)));
                var animated = new StartupAppearance(dark, contrast, false, 1);
                assertThat(pixels(render(animated, 0, 1))).isNotEqualTo(pixels(render(animated, 5, 1)));
                var image = render(still, 0, 1);
                assertThat(image.getRGB(30, 30) & 0xffffff).isEqualTo(StartupSplashColors.forAppearance(still).surface());
            }
        }
        assertThat(pixels(render(new StartupAppearance(false, false, true, 1), 0, 1)))
                .isNotEqualTo(pixels(render(new StartupAppearance(true, false, true, 1), 0, 1)));
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 1.25, 1.5, 1.75, 2, 2.5, 3})
    @DisplayName("分数及整数 DPI 使用设备变换绘制，内容保持在物理画布内")
    void rendersAtDeviceScale(double scale) {
        var image = render(new StartupAppearance(true, false, false, 1), 0, scale);
        assertThat(image.getWidth()).isEqualTo((int) Math.ceil(440 * scale));
        assertThat(image.getRGB((int) (30 * scale), (int) (30 * scale)) & 0xffffff)
                .isEqualTo(StartupSplashColors.forAppearance(new StartupAppearance(true, false, false, 1)).surface());
        assertThat(Arrays.stream(pixels(image)).anyMatch(pixel ->
                (pixel & 0xffffff) != (image.getRGB(30, 30) & 0xffffff))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 1.25, 1.5, 2})
    @DisplayName("细密图标缩小时融合明暗，透明像素不产生色边")
    void downsamplingFiltersFineDetailAndPreservesTransparency(double scale) {
        for (boolean transparent : List.of(false, true)) {
            var icon = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < icon.getHeight(); y++) {
                for (int x = 0; x < icon.getWidth(); x++) {
                    icon.setRGB(x, y, x % 2 == 0 ? 0xffffffff : transparent ? 0x00ff0000 : 0xff000000);
                }
            }
            for (boolean dark : List.of(false, true)) {
                var appearance = new StartupAppearance(dark, false, true, 1);
                var view = new StartupSplashView(icon, appearance, "Loading");
                var layout = view.arrange(new Dimension(440, 280));
                var image = render(view, scale);
                int centerX = (int) (layout.width() * scale / 2);
                int centerY = (int) ((layout.padding() + layout.iconSize() / 2) * scale);
                int surface = StartupSplashColors.forAppearance(appearance).surface();
                for (int y = centerY - 8; y < centerY + 8; y++) {
                    for (int x = centerX - 8; x < centerX + 8; x++) {
                        int pixel = image.getRGB(x, y);
                        for (int shift : List.of(0, 8, 16)) {
                            int expected = transparent ? (255 + ((surface >> shift) & 255)) / 2 : 128;
                            assertThat((pixel >> shift) & 255).isBetween(expected - 3, expected + 3);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("切换 DPI 与图标布局后重绘匹配新视图，不复用旧尺寸图像")
    void iconCacheTracksDeviceScaleAndLayout() throws Exception {
        BufferedImage icon;
        try (var input = StartupSplash.class.getResourceAsStream("/bootstrap/icon.png")) {
            icon = ImageIO.read(input);
        }
        var appearance = new StartupAppearance(true, false, true, 1);
        var reused = new StartupSplashView(icon, appearance, "Loading");
        for (Dimension space : List.of(new Dimension(440, 280), new Dimension(260, 180), new Dimension(440, 280))) {
            reused.arrange(space);
            for (double scale : List.of(1d, 1.25d, 2d, 1d)) {
                var fresh = new StartupSplashView(icon, appearance, "Loading");
                fresh.arrange(space);
                assertThat(pixels(render(reused, scale))).isEqualTo(pixels(render(fresh, scale)));
            }
        }
    }

    @Test
    @DisplayName("负坐标副屏、任务栏和过小工作区都限制窗口边界")
    void fitsTheUsableMonitorArea() {
        var work = StartupSplash.usableBounds(new Rectangle(-1920, -200, 1920, 1080), new Insets(0, 80, 40, 0));
        assertThat(work).isEqualTo(new Rectangle(-1840, -200, 1840, 1040));
        assertThat(work.contains(StartupSplash.centeredBounds(work, new Dimension(440, 280)))).isTrue();
        var tiny = new Rectangle(-100, 20, 200, 150);
        assertThat(tiny.contains(StartupSplash.centeredBounds(tiny, new Dimension(440, 280)))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"headless", "disabled", "close", "window"})
    @DisplayName("真实进程验证无头静默、预先关闭、幂等关闭、主窗交接与资源回收")
    void nativeLifecycle(String mode) throws Exception {
        if (!mode.equals("headless")) assumeTrue(Boolean.getBoolean("pixivdownload.test.nativeSplash"));
        Path directory = Files.createTempDirectory("pixiv-splash-test-");
        try {
            Files.writeString(directory.resolve("config.yaml"), "app.theme: dark\napp.language: zh-CN\n", StandardCharsets.UTF_8);
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Dfile.encoding=UTF-8", "-Djava.awt.headless=" + mode.equals("headless"),
                    "-Dpixivdownload.config-dir=" + directory,
                    "-Dpixivdownload.state-dir=" + directory.resolve("state"),
                    "-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as(output).isZero();
                assertThat(output).contains("SPLASH_PROBE_OK");
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        } finally {
            Files.deleteIfExists(directory.resolve("config.yaml"));
            Files.deleteIfExists(directory);
        }
    }

    static BufferedImage render(StartupAppearance appearance, int frame, double scale) {
        String status = MessageBundles.get(Locale.ENGLISH, "gui.launcher.splash.plugins");
        var view = new StartupSplashView(null, appearance, status);
        view.arrange(new Dimension(440, 280));
        view.update(appearance, status, frame);
        return render(view, scale);
    }

    private static BufferedImage render(StartupSplashView view, double scale) {
        var size = view.getPreferredSize();
        var image = new BufferedImage((int) Math.ceil(size.width * scale), (int) Math.ceil(size.height * scale), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.scale(scale, scale);
        try { view.paint(graphics); }
        finally { graphics.dispose(); }
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Utf8ConsoleStreams.install();
            if (!top.sywyar.pixivdownload.i18n.SystemLocaleDetector.detectAndApply().equals(Locale.SIMPLIFIED_CHINESE)) {
                throw new AssertionError("Configured startup language ignored");
            }
            String mode = args[0];
            int[] listenerCount = new int[1];
            if (!mode.equals("headless")) listenerCount[0] = Toolkit.getDefaultToolkit().getAWTEventListeners().length;
            try (var splash = new StartupSplash()) {
                if (mode.equals("disabled")) splash.close();
                splash.showStatus(MessageBundles.get(Locale.ENGLISH, "gui.launcher.splash.plugins"));
                if (mode.equals("close") || mode.equals("window")) {
                    EventQueue.invokeAndWait(() -> {
                        Window actual = Arrays.stream(Window.getWindows()).filter(window -> window.isVisible()
                                && window.getName().equals("pixivdownload-startup-splash")).findFirst().orElseThrow();
                        if (actual.isFocusableWindow() || actual.isAlwaysOnTop()) throw new AssertionError("Splash steals focus");
                        if (actual.getAccessibleContext().getAccessibleDescription().isBlank()) throw new AssertionError("Missing accessible status");
                        int background = actual.getBackground().getRGB() & 0xffffff;
                        if (background != StartupSplashColors.forAppearance(new StartupAppearance(true, false, false, 1)).surface()
                                && background != StartupSplashColors.forAppearance(new StartupAppearance(true, true, false, 1)).surface()) {
                            throw new AssertionError("Saved dark theme not applied");
                        }
                    });
                    if (mode.equals("window")) {
                        EventQueue.invokeAndWait(() -> {
                            Frame main = new Frame();
                            main.setSize(160, 100);
                            main.setVisible(true);
                            EventQueue.invokeLater(main::dispose);
                        });
                        EventQueue.invokeAndWait(() -> {});
                        EventQueue.invokeAndWait(() -> {
                            if (Arrays.stream(Window.getWindows()).anyMatch(window -> window.isDisplayable()
                                    && window.getName().equals("pixivdownload-startup-splash"))) {
                                throw new AssertionError("Splash did not close when the main window appeared");
                            }
                        });
                    }
                }
                splash.close();
                splash.close();
                splash.showStatus("ignored");
                if (!mode.equals("headless")) EventQueue.invokeAndWait(() -> {
                    if (Arrays.stream(Window.getWindows()).anyMatch(Window::isDisplayable)) throw new AssertionError("Window leaked");
                    if (Toolkit.getDefaultToolkit().getAWTEventListeners().length != listenerCount[0]) throw new AssertionError("Listener leaked");
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (animationAlive() && System.nanoTime() < deadline) Thread.sleep(10);
                if (animationAlive()) throw new AssertionError("Animation thread leaked");
            }
            System.out.println("SPLASH_PROBE_OK");
        }

        private static boolean animationAlive() {
            return Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive() && thread.getName().equals("startup-splash"));
        }
    }
}
