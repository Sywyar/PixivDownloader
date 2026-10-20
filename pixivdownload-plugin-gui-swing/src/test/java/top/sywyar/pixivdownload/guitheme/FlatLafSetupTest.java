package top.sywyar.pixivdownload.guitheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Swing 主题初始化")
class FlatLafSetupTest {

    @Test
    @DisplayName("韩语界面跳过不覆盖 Hangul 的已安装字体")
    void koreanLocaleSkipsInstalledFontWithoutHangulGlyphs() {
        String selected = FlatLafSetup.selectFontFamily(Locale.KOREAN,
                Set.of("Microsoft YaHei UI", "Malgun Gothic", "Dialog"),
                (name, sample) -> !name.equals("Microsoft YaHei UI") && sample.contains("한글"));

        assertThat(selected).isEqualTo("Malgun Gothic");
    }

    @Test
    @DisplayName("中文界面保留原有优先字体")
    void chineseLocaleKeepsExistingPriority() {
        String selected = FlatLafSetup.selectFontFamily(Locale.SIMPLIFIED_CHINESE,
                Set.of("Microsoft YaHei UI", "Dialog"),
                (name, sample) -> true);

        assertThat(selected).isEqualTo("Microsoft YaHei UI");
    }

    @Test
    @DisplayName("优先字体缺少导航符号时回退到兼容字体")
    void skipsPreferredFontWithoutNavigationGlyphs() {
        String selected = FlatLafSetup.selectFontFamily(Locale.SIMPLIFIED_CHINESE,
                Set.of("Microsoft YaHei UI", "Dialog"),
                (name, sample) -> !name.equals("Microsoft YaHei UI") && sample.contains("◀▶"));

        assertThat(selected).isEqualTo("Dialog");
    }

    @Test
    @DisplayName("没有兼容字体时交回系统默认字体")
    void returnsNullWithoutCompatibleFont() {
        String selected = FlatLafSetup.selectFontFamily(Locale.KOREAN,
                Set.of("Malgun Gothic", "Dialog"),
                (name, sample) -> false);

        assertThat(selected).isNull();
    }

    @Test
    @DisplayName("初始通知失败后撤回监听并保留原始错误")
    void initialNotificationFailureReleasesListener() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var previous = UIManager.getLookAndFeel();
            AtomicInteger notifications = new AtomicInteger();
            IllegalStateException failure = new IllegalStateException("listener failed");
            try {
                FlatLafSetup.apply(ThemePreference.LIGHT);
                assertThatThrownBy(() -> FlatLafSetup.openListener(appearance -> {
                    notifications.incrementAndGet();
                    throw failure;
                })).isSameAs(failure);
                FlatLafSetup.apply(ThemePreference.DARK);
                assertThat(notifications).hasValue(1);
            } finally {
                FlatLafSetup.close();
                try {
                    UIManager.setLookAndFeel(previous);
                } catch (Exception restoreFailure) {
                    throw new AssertionError(restoreFailure);
                }
            }
        });
    }
}
