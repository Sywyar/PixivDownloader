package top.sywyar.pixivdownload.guitheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 字体选择")
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
    @DisplayName("没有兼容字体时交回系统默认字体")
    void returnsNullWithoutCompatibleFont() {
        String selected = FlatLafSetup.selectFontFamily(Locale.KOREAN,
                Set.of("Malgun Gothic", "Dialog"),
                (name, sample) -> false);

        assertThat(selected).isNull();
    }
}
