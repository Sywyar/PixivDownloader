package top.sywyar.pixivdownload.onboarding;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OnboardingVisitFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"/pixiv-batch.html", "/pixiv-batch-alt.html"})
    void 下载工作台两个入口都会记录访问(String path) throws Exception {
        OnboardingProgressService progressService = mock(OnboardingProgressService.class);
        OnboardingVisitFilter filter = new OnboardingVisitFilter(progressService);

        filter.doFilterInternal(new MockHttpServletRequest("GET", path),
                new MockHttpServletResponse(), new MockFilterChain());

        verify(progressService).recordBatchVisit();
    }
}
