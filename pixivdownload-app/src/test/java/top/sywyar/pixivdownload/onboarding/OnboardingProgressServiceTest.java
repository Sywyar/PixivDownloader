package top.sywyar.pixivdownload.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 引导进度服务")
class OnboardingProgressServiceTest {

    @Test
    @DisplayName("步骤完成信号按中性 step id 记录并排序返回")
    void completedStepsAreStoredByStepId() {
        OnboardingProgressService service = new OnboardingProgressService();

        service.markStepCompleted("local-gallery-guide");
        service.markStepCompleted("alpha-guide");
        service.markStepCompleted("  ");

        assertThat(service.isStepCompleted("local-gallery-guide")).isTrue();
        assertThat(service.completedSteps()).containsExactly("alpha-guide", "local-gallery-guide");
    }
}
