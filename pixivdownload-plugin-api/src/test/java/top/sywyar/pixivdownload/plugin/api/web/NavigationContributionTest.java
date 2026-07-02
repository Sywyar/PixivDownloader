package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("导航 contribution 纯数据契约")
class NavigationContributionTest {

    @Test
    @DisplayName("旧构造器默认无 marker")
    void legacyConstructorsDefaultToNoMarkers() {
        NavigationContribution contribution = new NavigationContribution(
                "demo", "app.top", "demo", "nav.demo", "/demo.html", "grid", AccessPolicy.PUBLIC, 10);

        assertThat(contribution.markers()).isEmpty();
    }

    @Test
    @DisplayName("placements 与 markers 防御性拷贝")
    void setsAreDefensivelyCopied() {
        Set<String> placements = new HashSet<>(Set.of("app.top"));
        Set<String> markers = new HashSet<>(Set.of(NavigationMarkers.FIRST_DOWNLOAD_RESULT));

        NavigationContribution contribution = new NavigationContribution(
                "demo", placements, "demo", "nav.demo", "/demo.html", "grid",
                AccessPolicy.PUBLIC, 10, markers);

        placements.add("app.sidebar");
        markers.add("another-marker");

        assertThat(contribution.placements()).containsExactly("app.top");
        assertThat(contribution.markers()).containsExactly(NavigationMarkers.FIRST_DOWNLOAD_RESULT);
        assertThatThrownBy(() -> contribution.markers().add("blocked"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
