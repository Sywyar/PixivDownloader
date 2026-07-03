package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.web.NavigationController;

@DisplayName("NavigationController /api/navigation 可见性过滤与排序")
class NavigationControllerTest {

    private final SetupService setupService = mock(SetupService.class);
    private final NavigationRegistry registry = seededRegistry();
    private final NavigationController controller = new NavigationController(registry, setupService);

    /** 注册顺序刻意与 priority 不一致，以便验证响应按 priority 排序而非注册顺序。含一条 VISITOR 项验证身份模型。 */
    private static NavigationRegistry seededRegistry() {
        NavigationRegistry registry = new NavigationRegistry(new PluginRegistry(List.of()));
        registry.register("plug", List.of(
                new NavigationContribution("p3", "app.top", "ns", "nav.p3", "/p3.html", "i", AccessPolicy.PUBLIC, 30),
                new NavigationContribution("p1", "app.top", "ns", "nav.p1", "/p1.html", "i", AccessPolicy.PUBLIC, 10),
                new NavigationContribution("g2", "app.top", "ns", "nav.g2", "/g2.html", "i", AccessPolicy.INVITED_GUEST, 20),
                // VISITOR 项（如下载工作台）：访客 + 管理员可见，受邀访客不可见（点开本会 403）。
                new NavigationContribution("v1", "app.top", "ns", "nav.v1", "/v1.html", "i", AccessPolicy.VISITOR, 15),
                new NavigationContribution("a1", "app.top", "ns", "nav.a1", "/a1.html", "i", AccessPolicy.ADMIN, 5)));
        return registry;
    }

    private NavigationController controllerFor(NavigationRegistry registry) {
        return new NavigationController(registry, setupService);
    }

    private MockHttpServletRequest adminRequest() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        return new MockHttpServletRequest();
    }

    private static GuestInviteSession guestSession() {
        return new GuestInviteSession(1L, "code", true, false, false,
                true, Set.of(), true, Set.of(), true, Set.of(), true, Set.of());
    }

    private static PluginToggleProperties disabling(String... ids) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        for (String id : ids) {
            PluginToggleProperties.PluginToggle off = new PluginToggleProperties.PluginToggle();
            off.setEnabled(false);
            toggles.put(id, off);
        }
        return toggles;
    }

    private List<String> idsInPlacement(NavigationController controller, MockHttpServletRequest request, String placement) {
        return controller.navigation(request).stream()
                .filter(v -> v.placements().contains(placement))
                .map(NavigationController.NavigationView::id)
                .toList();
    }

    @Test
    @DisplayName("管理员范围可见全部导航项（含 VISITOR 项）并按 priority 升序排序")
    void adminSeesAllSortedByPriority() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        List<NavigationController.NavigationView> result = controller.navigation(request);

        assertThat(result).extracting(NavigationController.NavigationView::id)
                .containsExactly("a1", "p1", "v1", "g2", "p3");
    }

    @Test
    @DisplayName("访客邀请会话仅可见 PUBLIC 与 INVITED_GUEST（不含 VISITOR / ADMIN 项；访客判定优先于管理员范围）")
    void guestSeesPublicAndGuestRead() {
        // solo 模式下 hasAdminScope 对任意请求为真：访客判定必须优先，否则访客会看到 admin 项
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession());

        List<NavigationController.NavigationView> result = controller.navigation(request);

        // v1（VISITOR，如下载页）对受邀访客不可见：点开本会 403，故不进其导航栏。
        assertThat(result).extracting(NavigationController.NavigationView::id)
                .containsExactly("p1", "g2", "p3");
    }

    @Test
    @DisplayName("匿名请求（multi 访客）可见 PUBLIC 与 VISITOR 项（如下载页），不可见 INVITED_GUEST / ADMIN 项")
    void anonymousSeesPublicAndVisitor() {
        when(setupService.hasAdminScope(any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        List<NavigationController.NavigationView> result = controller.navigation(request);

        // multi 匿名访客能访问下载页（VISITOR）→ v1 可见；g2（INVITED_GUEST）/ a1（ADMIN）不可见。
        assertThat(result).extracting(NavigationController.NavigationView::id)
                .containsExactly("p1", "v1", "p3");
    }

    @Test
    @DisplayName("视图只暴露渲染字段（id/placements/labelI18nKey/href/icon/priority/markers），刻意不含 visibleTo")
    void viewExposesOnlyRenderFields() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        NavigationController.NavigationView first = controller.navigation(request).get(0);

        assertThat(first.id()).isEqualTo("a1");
        assertThat(first.placements()).containsExactly("app.top");
        assertThat(first.labelNamespace()).isEqualTo("ns");
        assertThat(first.labelI18nKey()).isEqualTo("nav.a1");
        assertThat(first.href()).isEqualTo("/a1.html");
        assertThat(first.icon()).isEqualTo("i");
        assertThat(first.priority()).isEqualTo(5);
        assertThat(first.markers()).isEmpty();
        assertThat(NavigationController.NavigationView.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "placements", "labelNamespace", "labelI18nKey", "href", "icon",
                        "priority", "markers");
    }

    @Test
    @DisplayName("labelNamespace 缺省（null）原样透传到视图：消费端据此回退，控制器不代填默认 namespace")
    void nullLabelNamespacePassesThroughToView() {
        NavigationRegistry reg = new NavigationRegistry(new PluginRegistry(List.of()));
        reg.register("plug", List.of(new NavigationContribution(
                "n-null", "app.top", null, "nav.n", "/n-null.html", "i", AccessPolicy.PUBLIC, 10)));
        when(setupService.hasAdminScope(any())).thenReturn(true);

        NavigationController.NavigationView view = controllerFor(reg).navigation(new MockHttpServletRequest()).get(0);

        assertThat(view.id()).isEqualTo("n-null");
        assertThat(view.labelNamespace()).isNull();
        assertThat(view.labelI18nKey()).isEqualTo("nav.n");
    }

    // ========== 来源层级 + placement 内 priority 排序 ==========

    @Test
    @DisplayName("gallery 已安装时管理员的 app.top placement 顺序：内置入口在前，外置 gallery 追加")
    void adminAppTopPlacementOrder() {
        NavigationController controller = controllerFor(
                new NavigationRegistry(new PluginRegistry(builtInWithGallery())));

        // gallery 已是外置插件，不再按内置来源排序；同一 placement 下追加在内置入口之后。
        assertThat(idsInPlacement(controller, adminRequest(), "app.top"))
                .containsExactly("monitor", "novel", "plugin-manage", "plugin-market", "gallery");
    }

    @Test
    @DisplayName("gallery 已安装时管理员的 app.sidebar placement 顺序：内置入口在前，外置 gallery 追加")
    void adminAppSidebarPlacementOrder() {
        NavigationController controller = controllerFor(
                new NavigationRegistry(new PluginRegistry(builtInWithGallery())));

        // 统计页用宿主中立的 app.sidebar slot：内置入口先排序，外置 gallery 在内置入口之后。
        // 插件管理现仅进顶部栏 placement（app.top），不再出现在主侧栏。
        assertThat(idsInPlacement(controller, adminRequest(), "app.sidebar"))
                .containsExactly("monitor", "invite-manage", "gallery");
    }

    @Test
    @DisplayName("禁用画廊：app.sidebar 去掉画廊入口，但 monitor / 邀请码管理仍按注册贡献显示")
    void disablingGalleryKeepsAppSidebarNonGalleryEntries() {
        NavigationController controller = controllerFor(new NavigationRegistry(
                new PluginRegistry(builtInWithGallery(), disabling("gallery"))));

        // 禁用画廊只撤掉画廊这一条贡献：主侧栏其余按权限应显示的入口不受影响、顺序不变（插件管理已移入顶部栏，不在主侧栏）。
        assertThat(idsInPlacement(controller, adminRequest(), "app.sidebar"))
                .containsExactly("monitor", "invite-manage")
                .doesNotContain("gallery");
    }

    @Test
    @DisplayName("外置插件即便 priority=-100，也排在全部内置项之后；外置之间再按 priority 排序")
    void externalPluginsFollowBuiltInsAndUsePriority() {
        PixivFeaturePlugin thirdParty = new TestNavPlugin("third-party-demo", List.of(
                new NavigationContribution("third-party-demo", "app.top", "ns", "nav.tp", "/third-party-demo.html",
                        "icon", AccessPolicy.ADMIN, -100)));
        List<PixivFeaturePlugin> plugins = new ArrayList<>(builtInWithGallery());
        plugins.add(thirdParty);
        NavigationController controller = controllerFor(new NavigationRegistry(new PluginRegistry(plugins)));

        assertThat(idsInPlacement(controller, adminRequest(), "app.top"))
                .containsExactly("monitor", "novel", "plugin-manage",
                        "plugin-market", "third-party-demo", "gallery");
    }

    // ========== placement 随插件禁用消失 ==========

    @Test
    @DisplayName("禁用画廊：其全部 placement 入口消失（含疑似重复页画廊图标、统计页画廊视图、小说页类型切换的画廊入口）")
    void disablingGalleryRemovesAllItsPlacements() {
        NavigationController controller = controllerFor(new NavigationRegistry(
                new PluginRegistry(builtInWithGallery(), disabling("gallery"))));
        MockHttpServletRequest admin = adminRequest();

        assertThat(controller.navigation(admin)).extracting(NavigationController.NavigationView::id)
                .doesNotContain("gallery", "gallery-type-switch",
                        "gallery-view-all", "gallery-view-authors", "gallery-view-series");
        // 小说页的「画廊↔小说」类型切换由画廊插件供给画廊入口；禁用画廊后该 placement 空。
        assertThat(idsInPlacement(controller, admin, "novel.type-switch")).isEmpty();
        // 统计页画廊视图 placement 空。
        assertThat(idsInPlacement(controller, admin, "stats.gallery-links")).isEmpty();
        // 疑似重复页图标区为空：画廊图标已随画廊禁用消失，统计图标因 stats 已外置、未安装而不在内置集合。
        assertThat(idsInPlacement(controller, admin, "duplicates.header-icons")).isEmpty();
    }

    @Test
    @DisplayName("禁用小说：画廊页类型切换的小说入口消失（gallery.type-switch placement 空）")
    void disablingNovelRemovesGalleryTypeSwitchEntry() {
        NavigationController controller = controllerFor(new NavigationRegistry(
                new PluginRegistry(builtInWithGallery(), disabling("novel"))));
        MockHttpServletRequest admin = adminRequest();

        assertThat(idsInPlacement(controller, admin, "gallery.type-switch")).isEmpty();
        assertThat(controller.navigation(admin)).extracting(NavigationController.NavigationView::id)
                .doesNotContain("novel", "novel-type-switch")
                .contains("gallery");
    }

    @Test
    @DisplayName("stats/duplicate 已外置、未安装：疑似重复页图标区只保留画廊插件贡献的图标")
    void builtInDuplicatesHeaderHasNoStatsIcon() {
        NavigationController controller = controllerFor(new NavigationRegistry(
                new PluginRegistry(builtInWithGallery())));

        // 统计入口由外置 stats 插件经 duplicates.header-icons placement 贡献；未安装时该 slot 只剩画廊图标。
        assertThat(idsInPlacement(controller, adminRequest(), "duplicates.header-icons"))
                .containsExactly("gallery");
    }

    @Test
    @DisplayName("受邀访客的 /api/navigation 不泄露任何 ADMIN 项（监控 / 疑似重复 / 邀请码管理）")
    void invitedGuestSeesNoAdminItems() {
        NavigationController controller = controllerFor(
                new NavigationRegistry(new PluginRegistry(builtInWithGallery())));
        when(setupService.hasAdminScope(any())).thenReturn(true); // solo 下恒真，访客判定须优先
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession());

        List<String> ids = controller.navigation(request).stream()
                .map(NavigationController.NavigationView::id).toList();

        // 受邀访客可见画廊 / 小说（INVITED_GUEST）；不可见 ADMIN 项（监控 / 疑似重复 / 邀请码管理 / 插件管理 / 插件市场）与 VISITOR 下载页。
        assertThat(ids).contains("gallery", "novel")
                .doesNotContain("monitor", "duplicate", "invite-manage", "plugin-manage", "plugin-market", "download-workbench");
    }

    /** 最小测试插件：以给定 id 与导航项构造，其余 contribution 为空（非内置 → 来源层级为第三方）。 */
    private static final class TestNavPlugin implements PixivFeaturePlugin {
        private final String id;
        private final List<NavigationContribution> navigation;

        TestNavPlugin(String id, List<NavigationContribution> navigation) {
            this.id = id;
            this.navigation = navigation;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "nav.label";
        }

        @Override
        public String description() {
            return "plugin.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<NavigationContribution> navigation() {
            return navigation;
        }
    }

    private static List<PixivFeaturePlugin> builtInWithGallery() {
        List<PixivFeaturePlugin> plugins = new ArrayList<>(BuiltInPlugins.createAll());
        plugins.add(new TestGalleryPlugin());
        return plugins;
    }
}
