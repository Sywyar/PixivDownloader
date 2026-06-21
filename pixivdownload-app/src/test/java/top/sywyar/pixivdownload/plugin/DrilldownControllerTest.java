package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownPlacements;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /api/drilldowns} 的可见性过滤、排序与禁用语义，以及 {@link DrilldownRegistry} 的注册期校验：
 * 画廊向统计页两个语义 placement（{@code stats.top-authors} / {@code stats.top-tags}）各贡献一条下钻模板；
 * 禁用画廊后这两条消失（统计页回到纯展示）；可见性按身份过滤、视图只暴露渲染字段（不含 visibleTo）；
 * 排序按来源层级 → priority → id；hrefTemplate 必须是同源绝对路径、id 全局唯一、placement 非空、pluginId 一致。
 */
@DisplayName("DrilldownController /api/drilldowns 可见性过滤、排序、禁用语义与注册期校验")
class DrilldownControllerTest {

    private static final String TOP_AUTHORS = DrilldownPlacements.STATS_TOP_AUTHORS;
    private static final String TOP_TAGS = DrilldownPlacements.STATS_TOP_TAGS;

    private final SetupService setupService = mock(SetupService.class);

    private DrilldownController controllerFor(DrilldownRegistry registry) {
        return new DrilldownController(registry, setupService);
    }

    private static DrilldownRegistry builtIn() {
        return new DrilldownRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    private static DrilldownRegistry disabling(String... ids) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        for (String id : ids) {
            PluginToggleProperties.PluginToggle off = new PluginToggleProperties.PluginToggle();
            off.setEnabled(false);
            toggles.put(id, off);
        }
        return new DrilldownRegistry(new PluginRegistry(BuiltInPlugins.createAll(), toggles));
    }

    private static DrilldownRegistry emptyRegistry() {
        return new DrilldownRegistry(new PluginRegistry(List.of()));
    }

    private MockHttpServletRequest adminRequest() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        return new MockHttpServletRequest();
    }

    private static GuestInviteSession guestSession() {
        return new GuestInviteSession(1L, "code", true, false, false,
                true, Set.of(), true, Set.of(), true, Set.of(), true, Set.of());
    }

    private List<String> ids(DrilldownController controller, MockHttpServletRequest request, String placement) {
        return controller.drilldowns(request, placement).stream()
                .map(DrilldownController.DrilldownView::id).toList();
    }

    // ========== 可见性 / 禁用语义 ==========

    @Test
    @DisplayName("管理员可见画廊向统计页两个 placement 贡献的下钻（作者 / 标签各一条）")
    void adminSeesGalleryDrilldowns() {
        DrilldownController controller = controllerFor(builtIn());
        assertThat(ids(controller, adminRequest(), TOP_AUTHORS)).containsExactly("gallery-stats-author");
        assertThat(ids(controller, adminRequest(), TOP_TAGS)).containsExactly("gallery-stats-tag");
        // 不带 placement 参数：返回全部可见下钻（两条）。
        assertThat(ids(controller, adminRequest(), null))
                .containsExactlyInAnyOrder("gallery-stats-author", "gallery-stats-tag");
    }

    @Test
    @DisplayName("禁用画廊：两个 placement 均无下钻贡献（统计页回到纯展示）")
    void disablingGalleryEmptiesDrilldowns() {
        DrilldownController controller = controllerFor(disabling("gallery"));
        assertThat(controller.drilldowns(adminRequest(), TOP_AUTHORS)).isEmpty();
        assertThat(controller.drilldowns(adminRequest(), TOP_TAGS)).isEmpty();
        assertThat(controller.drilldowns(adminRequest(), null)).isEmpty();
    }

    @Test
    @DisplayName("受邀访客可见画廊下钻（INVITED_GUEST）；multi 匿名访客不可见（下钻需 monitor 级身份）")
    void visibilityFollowsAudience() {
        // 受邀访客：solo 下 hasAdminScope 恒真，访客判定须优先，故仍解析为受邀访客身份。
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest guest = new MockHttpServletRequest();
        guest.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession());
        assertThat(ids(controllerFor(builtIn()), guest, TOP_AUTHORS)).containsExactly("gallery-stats-author");
        assertThat(ids(controllerFor(builtIn()), guest, TOP_TAGS)).containsExactly("gallery-stats-tag");

        // multi 匿名访客：画廊下钻为 INVITED_GUEST，不放行访客。
        when(setupService.hasAdminScope(any())).thenReturn(false);
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(controllerFor(builtIn()).drilldowns(anon, TOP_AUTHORS)).isEmpty();
        assertThat(controllerFor(builtIn()).drilldowns(anon, TOP_TAGS)).isEmpty();
        assertThat(controllerFor(builtIn()).drilldowns(anon, null)).isEmpty();
    }

    @Test
    @DisplayName("placement 过滤与视图字段：仅返回所求 placement；视图含 id/placements/hrefTemplate/priority，画廊模板保持现有筛选参数名")
    void placementFilterAndViewFields() {
        DrilldownController controller = controllerFor(builtIn());
        // 不存在的 placement → 空。
        assertThat(controller.drilldowns(adminRequest(), "no.such.placement")).isEmpty();

        DrilldownController.DrilldownView author = controller.drilldowns(adminRequest(), TOP_AUTHORS).get(0);
        assertThat(author.id()).isEqualTo("gallery-stats-author");
        assertThat(author.placements()).containsExactly(TOP_AUTHORS);
        assertThat(author.hrefTemplate())
                .isEqualTo("/pixiv-gallery.html?view=all&filterAuthorId={authorId}&filterAuthorName={authorName}");

        DrilldownController.DrilldownView tag = controller.drilldowns(adminRequest(), TOP_TAGS).get(0);
        assertThat(tag.id()).isEqualTo("gallery-stats-tag");
        assertThat(tag.placements()).containsExactly(TOP_TAGS);
        assertThat(tag.hrefTemplate()).isEqualTo(
                "/pixiv-gallery.html?view=all&filterTagId={tagId}&filterTag={tagName}&filterTagTranslated={tagTranslatedName}");
    }

    @Test
    @DisplayName("对外视图不暴露 visibleTo（内部访问策略不外泄；视图只含 id/placements/hrefTemplate/priority）")
    void viewDoesNotExposeVisibleTo() {
        assertThat(DrilldownController.DrilldownView.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "placements", "hrefTemplate", "priority")
                .doesNotContain("visibleTo");
    }

    @Test
    @DisplayName("排序：来源层级（内置先于第三方）→ placement 内 priority → id")
    void ordering() {
        DrilldownRegistry registry = emptyRegistry();
        // 同一 placement 下：内置插件 gallery（priority 故意填很大 99）+ 第三方插件多条（priority 1/5/5）。
        registry.register("gallery", List.of(
                new DrilldownContribution("gallery", "g-builtin", "X", "/g", AccessPolicy.PUBLIC, 99)));
        registry.register("third", List.of(
                new DrilldownContribution("third", "z-prio1", "X", "/z", AccessPolicy.PUBLIC, 1),
                new DrilldownContribution("third", "a-prio5", "X", "/a", AccessPolicy.PUBLIC, 5),
                new DrilldownContribution("third", "b-prio5", "X", "/b", AccessPolicy.PUBLIC, 5)));
        // 内置（rank 0）恒先于第三方（rank 1），即便其 priority(99) 远大于第三方；第三方内按 priority 升序、
        // 同 priority 按 id 升序：g-builtin → z-prio1 → a-prio5 → b-prio5。
        assertThat(ids(controllerFor(registry), adminRequest(), "X"))
                .containsExactly("g-builtin", "z-prio1", "a-prio5", "b-prio5");
    }

    // ========== DrilldownRegistry 注册期校验（hrefTemplate 同源 / id 唯一 / placement 非空 / pluginId 一致） ==========

    /** 最小合法下钻，只改 hrefTemplate（被校验的同源绝对路径字段）。 */
    private static DrilldownContribution drilldownWith(String hrefTemplate) {
        return new DrilldownContribution("p", "d", "host.slot", hrefTemplate, AccessPolicy.ADMIN, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",        // 伪协议
            "http://evil.example/x",      // 外部 http URL
            "https://evil.example/x",     // 外部 https URL
            "//evil.example/x",           // 协议相对 URL
            "/\\evil.example/x",          // 反斜杠变体（浏览器可能归一化为协议相对）
            "evil.html?a={b}",            // 相对路径（不以 / 开头）
            ""                            // 空串（非 null 但无效）
    })
    @DisplayName("非法 hrefTemplate 在注册期被拒：伪协议 / 外部 URL / 协议相对 / 反斜杠变体 / 相对路径 / 空串")
    void rejectsUnsafeHrefTemplate(String bad) {
        assertThatThrownBy(() -> emptyRegistry().register("p", List.of(drilldownWith(bad))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hrefTemplate");
    }

    @Test
    @DisplayName("合法同源绝对路径模板（含 query 与 {变量} 占位）通过注册")
    void acceptsSameOriginAbsolutePathTemplate() {
        assertThatCode(() -> emptyRegistry().register("p", List.of(drilldownWith(
                "/pixiv-gallery.html?view=all&filterAuthorId={authorId}&filterAuthorName={authorName}"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("注册期拒绝：重复 id / 空 placement / pluginId 不匹配")
    void rejectsInvalidContributions() {
        // 重复 id（跨贡献全局唯一）
        assertThatThrownBy(() -> emptyRegistry().register("p", List.of(
                new DrilldownContribution("p", "dup", "host.a", "/a", AccessPolicy.ADMIN, 1),
                new DrilldownContribution("p", "dup", "host.b", "/b", AccessPolicy.ADMIN, 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate drilldown id");

        // 空 placement 集合
        assertThatThrownBy(() -> emptyRegistry().register("p", List.of(
                new DrilldownContribution("p", "d", Set.of(), "/a", AccessPolicy.ADMIN, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placement");

        // 空白 placement 字符串
        assertThatThrownBy(() -> emptyRegistry().register("p", List.of(
                new DrilldownContribution("p", "d", " ", "/a", AccessPolicy.ADMIN, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placement");

        // pluginId 与登记方不一致
        assertThatThrownBy(() -> emptyRegistry().register("p", List.of(
                new DrilldownContribution("other", "d", "host.a", "/a", AccessPolicy.ADMIN, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pluginId mismatch");
    }
}
