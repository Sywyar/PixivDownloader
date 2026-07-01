package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WelcomePanelAnimationTest {

    @Test
    void fractionClampsToDurationAndEasingKeepsEndpoints() {
        long start = 1_000_000_000L;
        assertThat(WelcomePanel.SlideAnimator.fraction(start, start - 10, 320)).isZero();
        assertThat(WelcomePanel.SlideAnimator.fraction(start, start + 160_000_000L, 320))
                .isBetween(0.49f, 0.51f);
        assertThat(WelcomePanel.SlideAnimator.fraction(start, start + 500_000_000L, 320)).isEqualTo(1f);

        assertThat(WelcomePanel.ease(0f)).isCloseTo(0f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(WelcomePanel.ease(1f)).isCloseTo(1f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(WelcomePanel.ease(0.5f)).isBetween(0.7f, 0.9f);
    }

    @Test
    void stopPreventsLaterTicksFromEndingAnimation() {
        AtomicReference<Float> lastTick = new AtomicReference<>(0f);
        AtomicBoolean ended = new AtomicBoolean();
        WelcomePanel.SlideAnimator animator = new WelcomePanel.SlideAnimator(320, 16, lastTick::set,
                () -> ended.set(true));

        animator.startAtForTest(0L);
        animator.stop();
        animator.tick(500_000_000L);

        assertThat(animator.isRunning()).isFalse();
        assertThat(lastTick.get()).isZero();
        assertThat(ended).isFalse();
    }

    @Test
    void finalTickStopsAndRunsCompletion() {
        AtomicReference<Float> lastTick = new AtomicReference<>(0f);
        AtomicBoolean ended = new AtomicBoolean();
        WelcomePanel.SlideAnimator animator = new WelcomePanel.SlideAnimator(320, 16, lastTick::set,
                () -> ended.set(true));

        animator.startAtForTest(0L);
        animator.tick(320_000_000L);

        assertThat(lastTick.get()).isEqualTo(1f);
        assertThat(animator.isRunning()).isFalse();
        assertThat(ended).isTrue();
    }
}
