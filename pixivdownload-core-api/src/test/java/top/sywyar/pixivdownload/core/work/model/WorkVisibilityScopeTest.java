package top.sywyar.pixivdownload.core.work.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("作品可见性作用域纯值契约")
class WorkVisibilityScopeTest {

    @Test
    @DisplayName("无限制作用域不携带任一媒体类型限制")
    void unrestrictedScopeCarriesNoRestriction() {
        WorkVisibilityScope scope = WorkVisibilityScope.unrestricted();

        assertThat(scope.enforceVisibility()).isFalse();
        assertThat(scope.restrictionFor(WorkType.ARTWORK)).isNull();
        assertThat(scope.restrictionFor(WorkType.NOVEL)).isNull();
    }

    @Test
    @DisplayName("受限作用域严格区分插画与小说限制")
    void restrictedScopeKeepsWorkTypesSeparate() {
        WorkRestriction artwork = restriction(Set.of(0), List.of(11L), List.of(22L));
        WorkRestriction novel = restriction(Set.of(1), List.of(33L), List.of(44L));
        WorkVisibilityScope scope = WorkVisibilityScope.restricted(artwork, novel);

        assertThat(scope.enforceVisibility()).isTrue();
        assertThat(scope.restrictionFor(WorkType.ARTWORK)).isSameAs(artwork);
        assertThat(scope.restrictionFor(WorkType.NOVEL)).isSameAs(novel);
    }

    @Test
    @DisplayName("限制集合在构造时防御性复制")
    void restrictionCollectionsAreDefensivelyCopied() {
        Set<Integer> ratings = new HashSet<>(Set.of(0));
        List<Long> tags = new ArrayList<>(List.of(11L));
        List<Long> authors = new ArrayList<>(List.of(22L));
        WorkRestriction restriction = restriction(ratings, tags, authors);

        ratings.add(1);
        tags.add(12L);
        authors.add(23L);

        assertThat(restriction.allowedXRestricts()).containsExactly(0);
        assertThat(restriction.tagIds()).containsExactly(11L);
        assertThat(restriction.authorIds()).containsExactly(22L);
    }

    @Test
    @DisplayName("作用域构造拒绝受限状态缺投影或无限制状态夹带投影")
    void scopeConstructorEnforcesStateInvariant() {
        WorkRestriction restriction = restriction(Set.of(0), List.of(), List.of());

        assertThatThrownBy(() -> new WorkVisibilityScope(true, restriction, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkVisibilityScope(false, restriction, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkRestriction restriction(
            Set<Integer> ratings,
            List<Long> tags,
            List<Long> authors) {
        return new WorkRestriction(ratings, false, tags, false, authors);
    }
}
