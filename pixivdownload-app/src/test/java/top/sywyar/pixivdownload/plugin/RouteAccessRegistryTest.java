package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

@DisplayName("RouteAccessRegistry 路由注册中心")
class RouteAccessRegistryTest {

    private static RouteAccessRegistry emptyRegistry() {
        return new RouteAccessRegistry(new PluginRegistry(List.of()));
    }

    private static WebRouteContribution route(String pattern) {
        return new WebRouteContribution(pattern, AccessPolicy.ADMIN, Set.of(), false);
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集内置插件路由；gallery/novel-gallery 与其它外置能力路由不在内置快照")
    void collectsRoutesFromBuiltInPlugins() {
        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        // 可选能力已改为外置 PF4J 插件：其路由经外置插件 contribution 注册，绝不出现在内置快照里。
        assertThat(registry.routes())
                .noneMatch(registered -> Set.of(
                                "gallery", "novel-gallery", "stats", "duplicate", "push", "mail", "tts", "ai")
                        .contains(registered.pluginId()));

        RouteAccessRegistry withGallery = new RouteAccessRegistry(
                new PluginRegistry(List.of(new TestGalleryPlugin(), new TestNovelGalleryPlugin())));
        assertThat(withGallery.routes())
                .filteredOn(registered -> registered.pluginId().equals("gallery"))
                .extracting(registered -> registered.route().pathPattern())
                .contains("/api/gallery/artwork**");
        assertThat(withGallery.routes())
                .filteredOn(registered -> registered.pluginId().equals("novel-gallery"))
                .extracting(registered -> registered.route().pathPattern())
                .contains("/api/gallery/novel/**");
    }

    @Test
    @DisplayName("核心 Vue 运行时 /vendor/vue/** 由既有 /vendor/** 声明覆盖：解析为 VISITOR（已声明、不入 monitor、非未声明 404）")
    void resolvesVendoredVueRuntimeUnderVendorVisitor() {
        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        // Vue 运行时落在 /vendor/ 静态前缀下，由 CorePlugin 既有 visitor("/vendor/**") 声明 serving；
        // 当前实现不新增任何路由声明。resolve 命中（Optional 非空）即非「未声明」→ AuthFilter 不会 404；
        // 策略为 VISITOR（不派生进 monitor / 不收紧为 INVITED_GUEST）。
        assertThat(registry.resolve("/vendor/vue/vue.global.prod.js", HttpMethod.GET))
                .as("Vue 运行时应由既有 /vendor/** 声明解析为 VISITOR，不新增声明")
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.VISITOR);
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        RouteAccessRegistry registry = emptyRegistry();
        List<WebRouteContribution> routes = List.of(route("/demo.html"), route("/api/demo/**"));
        registry.register("demo", routes);
        List<RouteAccessRegistry.RegisteredRoute> first = registry.routes();
        registry.unregister("demo");
        assertThat(registry.routes()).isEmpty();
        registry.register("demo", routes);
        assertThat(registry.routes()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过路由的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.routes()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(route("/demo.html")));
        assertThatThrownBy(() -> registry.register("demo", List.of(route("/other.html"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("（模式, 方法集, 访问级别）三元组完全重复立即抛出；同模式不同三元组允许")
    void duplicateRouteTripleRejected() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("a", List.of(route("/shared/**")));
        assertThatThrownBy(() -> registry.register("b", List.of(route("/shared/**"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/shared/**");

        RouteAccessRegistry other = emptyRegistry();
        other.register("a", List.of(route("/shared/**")));
        other.register("b", List.of(new WebRouteContribution(
                "/shared/**", AccessPolicy.INVITED_GUEST, Set.of(HttpMethod.GET), false)));
        assertThat(other.routes()).hasSize(2);
    }

    @Test
    @DisplayName("路径模式必须以 / 开头且非空，pluginId 必须非空")
    void invalidInputRejected() {
        RouteAccessRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", List.of(route("no-slash"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of(route(" "))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(" ", List.of(route("/demo.html"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("routes() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(route("/demo.html")));
        List<RouteAccessRegistry.RegisteredRoute> routes = registry.routes();
        assertThatThrownBy(() -> routes.add(new RouteAccessRegistry.RegisteredRoute("x", route("/x.html"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── 有效路由解析 resolve(path, method)：最具体声明 + 方法（窄声明覆盖宽前缀，宽前缀不吞窄端点）──

    private static WebRouteContribution route(String pattern, AccessPolicy policy, HttpMethod... methods) {
        return new WebRouteContribution(pattern, policy, Set.of(methods), false);
    }

    @Test
    @DisplayName("resolve：精确声明优先于宽前缀声明（窄端点不被宽前缀吞掉）")
    void resolveExactOverridesPrefix() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(
                route("/api/x/**", AccessPolicy.ADMIN),
                route("/api/x/open", AccessPolicy.VISITOR_AND_INVITED_GUEST)));
        assertThat(registry.resolve("/api/x/open", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.VISITOR_AND_INVITED_GUEST);
        // 前缀下其它路径仍解析到宽前缀策略
        assertThat(registry.resolve("/api/x/other", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.ADMIN);
    }

    @Test
    @DisplayName("resolve：更长前缀优先于更短前缀")
    void resolveLongerPrefixWins() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(
                route("/api/x/**", AccessPolicy.LOCAL),
                route("/api/x/img/**", AccessPolicy.INVITED_GUEST)));
        assertThat(registry.resolve("/api/x/img/1", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.INVITED_GUEST);
    }

    @Test
    @DisplayName("resolve：同模式下显式方法集优先于空方法集（按请求方法择一）")
    void resolveMethodSpecificOverridesEmptyMethods() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(
                route("/api/x/**", AccessPolicy.ADMIN),
                route("/api/x/act", AccessPolicy.INVITED_GUEST, HttpMethod.POST)));
        // POST：精确 + 显式 POST 胜出
        assertThat(registry.resolve("/api/x/act", HttpMethod.POST))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.INVITED_GUEST);
        // GET：精确 POST 声明不接受 GET，回落到宽前缀 ADMIN
        assertThat(registry.resolve("/api/x/act", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.ADMIN);
    }

    @Test
    @DisplayName("resolve：同等特异性多候选含 PUBLIC 时 PUBLIC 胜出（无条件公开，可达面是全集）")
    void resolvePublicWinsOnEqualSpecificityTie() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("a", List.of(route("/js/x.js", AccessPolicy.PUBLIC)));
        registry.register("b", List.of(route("/js/x.js", AccessPolicy.VISITOR_AND_INVITED_GUEST)));
        assertThat(registry.resolve("/js/x.js", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.PUBLIC);
    }

    @Test
    @DisplayName("resolve：同等特异性不同策略且都非 PUBLIC → 声明歧义立即抛出（不静默依赖注册顺序）")
    void resolveAmbiguousNonPublicFailsFast() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("a", List.of(route("/api/x", AccessPolicy.ADMIN)));
        registry.register("b", List.of(route("/api/x", AccessPolicy.INVITED_GUEST)));
        assertThatThrownBy(() -> registry.resolve("/api/x", HttpMethod.GET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/api/x");
    }

    @Test
    @DisplayName("resolve：无任何匹配返回空（AuthFilter 据此统一 404）")
    void resolveNoMatchIsEmpty() {
        assertThat(emptyRegistry().resolve("/api/nope", HttpMethod.GET)).isEmpty();
    }

    @Test
    @DisplayName("resolve：TTS 外置插件注册后 /api/tts/** 才进入路由快照")
    void resolveTtsRoutesOnlyAfterExternalPluginRegistration() {
        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.resolve("/api/tts/edge/synthesize", HttpMethod.POST)).isEmpty();

        registry.register("tts", List.of(WebRouteContribution.visitorAndInvitedGuest("/api/tts/**")));

        assertThat(registry.resolve("/api/tts/edge/synthesize", HttpMethod.POST))
                .get()
                .satisfies(r -> {
                    assertThat(r.pluginId()).isEqualTo("tts");
                    assertThat(r.route().accessPolicy()).isEqualTo(AccessPolicy.VISITOR_AND_INVITED_GUEST);
                });
        assertThat(registry.resolve("/api/tts/edge/voices", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.VISITOR_AND_INVITED_GUEST);
        assertThat(registry.resolve("/api/tts/some-admin-op", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.VISITOR_AND_INVITED_GUEST);
    }

    @Test
    @DisplayName("resolve：内置共享脚本 /js/pixiv-i18n.js 在 PUBLIC + VISITOR_AND_INVITED_GUEST 双声明下解析到 PUBLIC")
    void resolveBuiltInSharedScriptResolvesToPublic() {
        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.resolve("/js/pixiv-i18n.js", HttpMethod.GET))
                .get().extracting(r -> r.route().accessPolicy())
                .isEqualTo(AccessPolicy.PUBLIC);
    }

    @Test
    @DisplayName("isDeclared(path, method) 是 method-aware：仅声明某方法的 URL 用别的方法访问视为未声明")
    void isDeclaredIsMethodAware() {
        RouteAccessRegistry registry = emptyRegistry();
        registry.register("demo", List.of(route("/api/only-post", AccessPolicy.INVITED_GUEST, HttpMethod.POST)));
        assertThat(registry.isDeclared("/api/only-post", HttpMethod.POST)).isTrue();
        assertThat(registry.isDeclared("/api/only-post", HttpMethod.GET)).isFalse();
        // 但更宽的全方法声明会覆盖所有方法
        registry.register("wide", List.of(route("/api/only-post/**", AccessPolicy.ADMIN)));
        assertThat(registry.isDeclared("/api/only-post/x", HttpMethod.GET)).isTrue();
        // path-level 助手忽略方法
        assertThat(registry.isDeclared("/api/only-post")).isTrue();
    }
}
