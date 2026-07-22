package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("默认启动落点 contribution 纯数据契约")
class StartupRouteContributionTest {

    @Test
    @DisplayName("两参构造默认不绑定首选启动上下文")
    void legacyConstructorDefaultsPreferredContextsToEmpty() {
        StartupRouteContribution contribution =
                new StartupRouteContribution("/demo.html", 10);

        assertThat(contribution.preferredContexts()).isEmpty();
    }

    @Test
    @DisplayName("首选启动上下文集合做防御性拷贝且不可变")
    void preferredContextsAreDefensivelyCopied() {
        Set<StartupRouteContext> contexts = new HashSet<>(Set.of(StartupRouteContext.SOLO));
        StartupRouteContribution contribution =
                new StartupRouteContribution("/demo.html", 10, contexts);

        contexts.add(StartupRouteContext.MULTI);

        assertThat(contribution.preferredContexts()).containsExactly(StartupRouteContext.SOLO);
        assertThatThrownBy(() -> contribution.preferredContexts().add(StartupRouteContext.MULTI))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
