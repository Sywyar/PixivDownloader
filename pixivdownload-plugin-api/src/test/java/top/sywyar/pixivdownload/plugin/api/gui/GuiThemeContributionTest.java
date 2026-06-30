package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GUI 主题 contribution 契约")
class GuiThemeContributionTest {

    @Test
    @DisplayName("贡献声明稳定 id、本地化显示名、深浅状态、EDT 应用入口与可关闭监听 session")
    void contributionExposesThemeContract() throws Exception {
        AtomicInteger applied = new AtomicInteger();
        AtomicReference<GuiThemeAppearance> observed = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);

        GuiThemeContribution theme = new GuiThemeContribution(
                "demo.dark",
                locale -> Locale.ENGLISH.getLanguage().equals(locale.getLanguage()) ? "Demo Dark" : "演示深色",
                GuiThemeAppearance.DARK,
                applied::incrementAndGet,
                listener -> {
                    listener.appearanceChanged(GuiThemeAppearance.SYSTEM);
                    return () -> closed.set(true);
                });

        assertThat(theme.themeId()).isEqualTo("demo.dark");
        assertThat(theme.displayName(Locale.ENGLISH)).isEqualTo("Demo Dark");
        assertThat(theme.displayName(Locale.CHINESE)).isEqualTo("演示深色");
        assertThat(theme.appearance()).isEqualTo(GuiThemeAppearance.DARK);

        assertThatThrownBy(theme::applyOnEventDispatchThread)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.dark");
        EventQueue.invokeAndWait(() -> {
            try {
                theme.applyOnEventDispatchThread();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertThat(applied).hasValue(1);

        GuiThemeListenerSession session = theme.openListener(observed::set);
        assertThat(observed).hasValue(GuiThemeAppearance.SYSTEM);
        session.close();
        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("无监听器贡献返回 no-op session，仍可关闭")
    void noListenerContributionReturnsNoopSession() {
        GuiThemeContribution theme = new GuiThemeContribution(
                "demo.light",
                locale -> "Demo Light",
                GuiThemeAppearance.LIGHT,
                () -> {
                });

        GuiThemeListenerSession session = theme.openListener(appearance -> {
        });
        session.close();
    }

    @Test
    @DisplayName("非法契约输入立即拒绝：id 非空，函数与状态不可为 null，显示名不可为空")
    void invalidContributionRejected() {
        assertThatThrownBy(() -> new GuiThemeContribution(" ", locale -> "x", GuiThemeAppearance.UNKNOWN, () -> {
        })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuiThemeContribution("demo", null, GuiThemeAppearance.UNKNOWN, () -> {
        })).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GuiThemeContribution("demo", locale -> "x", null, () -> {
        })).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GuiThemeContribution("demo", locale -> "x", GuiThemeAppearance.UNKNOWN, null))
                .isInstanceOf(NullPointerException.class);

        GuiThemeContribution blankName = new GuiThemeContribution(
                "demo.blank", locale -> " ", GuiThemeAppearance.UNKNOWN, () -> {
        });
        assertThatThrownBy(() -> blankName.displayName(Locale.ENGLISH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.blank");
    }
}
