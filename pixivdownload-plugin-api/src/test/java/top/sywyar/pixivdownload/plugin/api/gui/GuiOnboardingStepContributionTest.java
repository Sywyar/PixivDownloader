package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GUI 引导步骤 contribution 纯数据契约")
class GuiOnboardingStepContributionTest {

    @Test
    @DisplayName("bullet key 列表做防御性拷贝且不可变")
    void bulletKeysAreDefensivelyCopied() {
        List<String> bullets = new ArrayList<>(List.of("point.one"));
        GuiOnboardingStepContribution contribution = new GuiOnboardingStepContribution(
                "demo",
                "demo-guide",
                "demo",
                "title",
                "body",
                bullets,
                "button",
                "/demo.html",
                "waiting",
                "demo-guide",
                10);

        bullets.add("point.two");

        assertThat(contribution.bulletKeys()).containsExactly("point.one");
        assertThatThrownBy(() -> contribution.bulletKeys().add("point.two"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null bullet key 列表归一化为空列表")
    void nullBulletKeysNormalizeToEmptyList() {
        GuiOnboardingStepContribution contribution = new GuiOnboardingStepContribution(
                "demo",
                "demo-guide",
                "demo",
                "title",
                "body",
                null,
                "button",
                "/demo.html",
                "waiting",
                "demo-guide",
                10);

        assertThat(contribution.bulletKeys()).isEmpty();
    }
}
