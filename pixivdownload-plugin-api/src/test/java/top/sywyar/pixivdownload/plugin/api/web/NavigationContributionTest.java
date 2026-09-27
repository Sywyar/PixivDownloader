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
    @DisplayName("便利构造默认使用空 marker 集合")
    void convenienceConstructorsDefaultToNoMarkers() {
        NavigationContribution singlePlacement = new NavigationContribution(
                "demo", "app.top", "demo", "nav.demo", "/demo.html", "grid", AccessPolicy.PUBLIC, 10);
        NavigationContribution multiplePlacements = new NavigationContribution(
                "demo", Set.of("app.top", "app.sidebar"), "demo", "nav.demo",
                "/demo.html", "grid", AccessPolicy.PUBLIC, 10);

        assertThat(singlePlacement.markers()).isEmpty();
        assertThat(multiplePlacements.markers()).isEmpty();
        assertThat(singlePlacement.descriptionI18nKey()).isEmpty();
        assertThat(multiplePlacements.descriptionI18nKey()).isEmpty();
    }

    @Test
    @DisplayName("导航说明保留贡献方消息键，缺省说明规范为空")
    void optionalDescriptionBelongsToNavigationNamespace() {
        var described = new NavigationContribution("sample", Set.of(NavigationPlacements.DESKTOP_SECURITY_ACTIONS),
                "sample", "nav.title", "/sample.html", "shield", AccessPolicy.ADMIN, 10,
                Set.of(), " nav.description ");
        assertThat(described.descriptionI18nKey()).isEqualTo("nav.description");
        assertThat(described.labelNamespace()).isEqualTo("sample");
        assertThat(new NavigationContribution("sample", Set.of("app.top"), "sample", "nav.title",
                "/sample.html", "shield", AccessPolicy.ADMIN, 10, Set.of(), null).descriptionI18nKey()).isEmpty();
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
