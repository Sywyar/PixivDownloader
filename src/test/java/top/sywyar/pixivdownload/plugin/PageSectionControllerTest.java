package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /api/page-sections} 的可见性过滤与禁用语义：画廊向统计页（stats.sidebar.sections）贡献「视图 / 收藏夹」
 * 两个区块；禁用画廊后该 placement 为空（统计页不残留任何画廊业务入口）；可见性按身份过滤、视图只暴露渲染字段。
 */
@DisplayName("PageSectionController /api/page-sections 可见性过滤与禁用语义")
class PageSectionControllerTest {

    private static final String STATS = "stats.sidebar.sections";

    private final SetupService setupService = mock(SetupService.class);

    private PageSectionController controllerFor(PageSectionRegistry registry) {
        return new PageSectionController(registry, setupService);
    }

    private static PageSectionRegistry builtIn() {
        return new PageSectionRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    private static PageSectionRegistry disabling(String... ids) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        for (String id : ids) {
            PluginToggleProperties.PluginToggle off = new PluginToggleProperties.PluginToggle();
            off.setEnabled(false);
            toggles.put(id, off);
        }
        return new PageSectionRegistry(new PluginRegistry(BuiltInPlugins.createAll(), toggles));
    }

    private MockHttpServletRequest adminRequest() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        return new MockHttpServletRequest();
    }

    private static GuestInviteSession guestSession() {
        return new GuestInviteSession(1L, "code", true, false, false,
                true, Set.of(), true, Set.of(), true, Set.of(), true, Set.of());
    }

    private List<String> ids(PageSectionController controller, MockHttpServletRequest request, String placement) {
        return controller.sections(request, placement).stream()
                .map(PageSectionController.PageSectionView::id).toList();
    }

    @Test
    @DisplayName("管理员可见画廊向统计页贡献的两个区块（视图在前、收藏夹其次，按 priority 排序）")
    void adminSeesGalleryStatsSections() {
        assertThat(ids(controllerFor(builtIn()), adminRequest(), STATS))
                .containsExactly("gallery-stats-views", "gallery-stats-collections");
    }

    @Test
    @DisplayName("禁用画廊：统计页区块 slot 为空（统计页不残留任何画廊业务入口）")
    void disablingGalleryEmptiesStatsSections() {
        PageSectionController controller = controllerFor(disabling("gallery"));
        assertThat(controller.sections(adminRequest(), STATS)).isEmpty();
        assertThat(controller.sections(adminRequest(), null)).isEmpty();
    }

    @Test
    @DisplayName("受邀访客可见画廊区块（INVITED_GUEST）；multi 匿名访客不可见（区块需 monitor 级身份）")
    void visibilityFollowsAudience() {
        // 受邀访客：solo 下 hasAdminScope 恒真，访客判定须优先，故仍解析为受邀访客身份。
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest guest = new MockHttpServletRequest();
        guest.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession());
        assertThat(ids(controllerFor(builtIn()), guest, STATS))
                .containsExactly("gallery-stats-views", "gallery-stats-collections");

        // multi 匿名访客：画廊区块为 INVITED_GUEST，不放行访客。
        when(setupService.hasAdminScope(any())).thenReturn(false);
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(controllerFor(builtIn()).sections(anon, STATS)).isEmpty();
    }

    @Test
    @DisplayName("placement 过滤：仅返回所求 placement 的区块；视图只暴露渲染字段（含 navPlacement / actionHref / moduleUrl）")
    void placementFilterAndViewFields() {
        PageSectionController controller = controllerFor(builtIn());
        // 不存在的 placement → 空。
        assertThat(controller.sections(adminRequest(), "no.such.placement")).isEmpty();

        List<PageSectionController.PageSectionView> sections = controller.sections(adminRequest(), STATS);
        PageSectionController.PageSectionView views = sections.get(0);
        assertThat(views.id()).isEqualTo("gallery-stats-views");
        assertThat(views.placement()).isEqualTo(STATS);
        assertThat(views.navPlacement()).isEqualTo("stats.gallery-links");
        assertThat(views.moduleUrl()).isNull();
        assertThat(views.actionHref()).isNull();

        PageSectionController.PageSectionView collections = sections.get(1);
        assertThat(collections.id()).isEqualTo("gallery-stats-collections");
        assertThat(collections.navPlacement()).isNull();
        assertThat(collections.actionHref()).isEqualTo("/pixiv-gallery.html?view=all&createCollection=1");
        assertThat(collections.moduleUrl()).isEqualTo("/pixiv-gallery/gallery-stats-embed.js");
    }

    // ========== actionHref / moduleUrl 安全边界（PageSectionRegistry.validate 同源绝对路径校验） ==========

    private static PageSectionRegistry emptyRegistry() {
        return new PageSectionRegistry(new PluginRegistry(List.of()));
    }

    /** 最小合法区块，只改 actionHref / moduleUrl 两个被校验的可选字段。 */
    private static PageSectionContribution sectionWith(String actionHref, String moduleUrl) {
        return new PageSectionContribution("p", "sec", "host.slot", "stats:title",
                null, actionHref, null, null, moduleUrl, AccessPolicy.ADMIN, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",        // 伪协议
            "http://evil.example/x",      // 外部 http URL
            "https://evil.example/x",     // 外部 https URL
            "//evil.example/x",           // 协议相对 URL
            "/\\evil.example/x",          // 反斜杠变体（浏览器可能归一化为协议相对）
            "evil.html",                  // 相对路径（不以 / 开头）
            ""                            // 空串（非 null 但无效）
    })
    @DisplayName("非法 actionHref 在注册期被拒：伪协议 / 外部 URL / 协议相对 / 反斜杠变体 / 相对路径 / 空串")
    void rejectsUnsafeActionHref(String bad) {
        assertThatThrownBy(() -> emptyRegistry().register("p", List.of(sectionWith(bad, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actionHref");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "http://evil.example/x.js",
            "https://evil.example/x.js",
            "//evil.example/x.js",
            "/\\evil.example/x.js",
            "relative.js",
            ""
    })
    @DisplayName("非法 moduleUrl 在注册期被拒：伪协议 / 外部 URL / 协议相对 / 反斜杠变体 / 相对路径 / 空串")
    void rejectsUnsafeModuleUrl(String bad) {
        assertThatThrownBy(() -> emptyRegistry().register("p", List.of(sectionWith(null, bad))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("moduleUrl");
    }

    @Test
    @DisplayName("合法同源绝对路径（含 query）通过；actionHref / moduleUrl 不提供（null）也通过")
    void acceptsSameOriginAbsolutePathsAndNulls() {
        assertThatCode(() -> emptyRegistry().register("p", List.of(sectionWith(
                "/pixiv-gallery.html?view=all&createCollection=1", "/pixiv-gallery/gallery-stats-embed.js"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> emptyRegistry().register("p", List.of(sectionWith(null, null))))
                .doesNotThrowAnyException();
    }
}
