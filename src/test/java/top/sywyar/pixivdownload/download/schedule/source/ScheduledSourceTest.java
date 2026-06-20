package top.sywyar.pixivdownload.download.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划任务来源 provider 的发现模式判定 / 账号私有判定 / 系列合订适用判定 / 身份派生测试（纯函数，无需 Spring）。
 *
 * <p>其中模式判定与账号私有判定的用例自 {@code ScheduleExecutorFilterTest} 的 {@code isWatermarkMode} /
 * {@code isAccountScopedType} 迁来——这两段按类型判定的逻辑已随发现 / 派发一并迁入各 {@link ScheduledSource}，
 * 调度器不再按 {@link top.sywyar.pixivdownload.core.schedule.ScheduledTaskType} 枚举 switch 判定。
 */
@DisplayName("计划任务来源 provider 行为")
class ScheduledSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode src(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("水位线模式：USER_NEW / USER_REQUEST / FOLLOW_LATEST 恒走 ID 水位线")
    void watermarkSourcesAlwaysWatermark() throws Exception {
        assertThat(new UserNewSource().mode(src("{}"))).isEqualTo(DiscoveryMode.WATERMARK);
        assertThat(new UserRequestSource().mode(src("{}"))).isEqualTo(DiscoveryMode.WATERMARK);
        assertThat(new FollowLatestSource().mode(src("{}"))).isEqualTo(DiscoveryMode.WATERMARK);
    }

    @Test
    @DisplayName("SEARCH：date_d + maxPages==-1 走水位线；非 date_d + -1 走已下载边界；其余走全量")
    void searchModeByParams() throws Exception {
        SearchSource s = new SearchSource();
        assertThat(s.mode(src("{\"order\":\"date_d\",\"maxPages\":-1}"))).isEqualTo(DiscoveryMode.WATERMARK);
        assertThat(s.mode(src("{\"order\":\"popular_d\",\"maxPages\":-1}")))
                .isEqualTo(DiscoveryMode.DOWNLOADED_BOUNDARY);
        assertThat(s.mode(src("{\"order\":\"date_d\",\"maxPages\":3}"))).isEqualTo(DiscoveryMode.FULL);
        // 默认 maxPages=3、order=date_d → 固定页全量
        assertThat(s.mode(src("{}"))).isEqualTo(DiscoveryMode.FULL);
    }

    @Test
    @DisplayName("全量 / 珍藏集模式：SERIES / MY_BOOKMARKS 走全量，COLLECTION 走珍藏集路径")
    void fullAndCollectionModes() throws Exception {
        assertThat(new SeriesSource().mode(src("{}"))).isEqualTo(DiscoveryMode.FULL);
        assertThat(new MyBookmarksSource().mode(src("{}"))).isEqualTo(DiscoveryMode.FULL);
        assertThat(new CollectionSource().mode(src("{}"))).isEqualTo(DiscoveryMode.COLLECTION);
    }

    @Test
    @DisplayName("账号私有来源：收藏 / 关注新作 / 珍藏集为账号私有，画师 / 搜索 / 系列不是")
    void accountScopedSources() {
        assertThat(new MyBookmarksSource().accountScoped()).isTrue();
        assertThat(new FollowLatestSource().accountScoped()).isTrue();
        assertThat(new CollectionSource().accountScoped()).isTrue();
        assertThat(new UserNewSource().accountScoped()).isFalse();
        assertThat(new UserRequestSource().accountScoped()).isFalse();
        assertThat(new SearchSource().accountScoped()).isFalse();
        assertThat(new SeriesSource().accountScoped()).isFalse();
    }

    @Test
    @DisplayName("系列合订仅系列来源适用")
    void seriesMergeOnlyForSeries() {
        assertThat(new SeriesSource().seriesMergeApplies()).isTrue();
        assertThat(new UserNewSource().seriesMergeApplies()).isFalse();
        assertThat(new MyBookmarksSource().seriesMergeApplies()).isFalse();
        assertThat(new CollectionSource().seriesMergeApplies()).isFalse();
    }

    @Test
    @DisplayName("身份派生：规范 type 由枚举名派生为小写短横线，legacy 名为枚举名本身")
    void identityDerivation() {
        assertThat(new UserNewSource().type()).isEqualTo("user-new");
        assertThat(new UserNewSource().legacyTypeNames()).containsExactly("USER_NEW");
        assertThat(new MyBookmarksSource().type()).isEqualTo("my-bookmarks");
        assertThat(new MyBookmarksSource().legacyTypeNames()).containsExactly("MY_BOOKMARKS");
        assertThat(new FollowLatestSource().type()).isEqualTo("follow-latest");
    }
}
