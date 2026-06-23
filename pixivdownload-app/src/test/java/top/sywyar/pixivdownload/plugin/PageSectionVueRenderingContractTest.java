package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 页面区块渲染 Vue reactive 化的静态接线守卫：保证 reactive 主路径、命令式回退、职责分离与运行时单一来源
 * 不被后续改动悄悄破坏。纯静态资源内容断言（不启 Spring 上下文），与 {@link NovelSearchVueRenderingContractTest}
 * 同一形态。运行态行为另由 {@code src/test/js/pixiv-page-sections.test.js} 验证（页面区块 live 于统计页侧栏）。
 * <p>具体守住：
 * <ul>
 *   <li>区块渲染器 {@code pixiv-page-sections.js} 的区块**骨架**走 Vue（{@code PixivVue.ensure} 懒加载运行时、
 *       {@code Vue.reactive} 共享 i18n、经统一 helper {@code PixivVue.mountOn} 挂组件），保留命令式回退 {@code sectionHtml}，
 *       挂载失败收敛为 {@code console.warn};</li>
 *   <li><b>职责分离不变量</b>：骨架模板把内嵌 {@code <nav data-nav-slot>} 与 {@code .page-section-body} 渲染为
 *       <b>空</b> Vue 元素（无 Vue 子节点）——Vue 重渲染不动其实际子节点，故 PixivNav 填的链接 / 贡献方模块填的列表不被覆盖;</li>
 *   <li>渲染后委托：{@code PixivNav.refresh()}（填内嵌导航）+ {@code loadPendingModules()}（贡献方模块）+ 派发
 *       {@code pixivpagesections:rendered}（贡献方据此填容器）;</li>
 *   <li>运行时单一来源：区块渲染器不自带 / 不硬编码 {@code /vendor/vue/}。</li>
 * </ul>
 */
@DisplayName("Vue reactive 渲染契约守卫：页面区块骨架 reactive 主路径 + 职责分离 + 命令式回退 + 运行时单一来源")
class PageSectionVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String MODULE = "js/pixiv-page-sections.js";

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = PageSectionVueRenderingContractTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new NoSuchFileException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sliceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertThat(start).as("源码缺少起始标记: " + startMarker).isGreaterThanOrEqualTo(0);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(end).as("源码缺少结束标记: " + endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("区块骨架走 Vue reactive：PixivVue.ensure 懒加载、Vue.reactive 共享 i18n、PixivVue.mountOn 挂组件")
    void pageSectionUsesVueReactivePath() throws IOException {
        String js = read(MODULE);
        assertThat(js).as("应经共享 helper 懒加载核心 Vue 运行时").contains("PixivVue.ensure(");
        assertThat(js).as("应用 Vue.reactive 建共享 i18n 状态").contains("Vue.reactive(");
        assertThat(js).as("应经统一 helper mountOn 把 Vue 组件挂到 section slot 元素").contains("PixivVue.mountOn(");
        assertThat(js).as("应有据 slot 构造 Vue 组件的工厂").contains("function buildSectionComponent(");
        assertThat(js).as("应有 Vue 区块骨架模板").contains("SECTION_TEMPLATE");
    }

    @Test
    @DisplayName("职责分离不变量：内嵌 <nav data-nav-slot> 与 .page-section-body 在骨架模板中为空 Vue 元素（无 Vue 子节点）")
    void embeddedNavAndBodyHaveNoVueChildren() throws IOException {
        String template = sliceBetween(read(MODULE), "var SECTION_TEMPLATE =", "function buildSectionComponent(");
        assertThat(template).as("骨架渲染内嵌导航 slot 容器").contains(":data-nav-slot=\"s.navPlacement\"");
        assertThat(template)
                .as("内嵌 <nav> 必须为空元素（无 Vue 子节点）——PixivNav 填的链接才不被 Vue 重渲染覆盖")
                .contains("></nav>");
        assertThat(template)
                .as("moduleUrl body 必须为空元素（无 Vue 子节点）——贡献方模块填的列表才不被 Vue 重渲染覆盖")
                .contains("class=\"page-section-body\" :data-section-id=\"s.id\"></div>");
    }

    @Test
    @DisplayName("保留命令式回退 + 渲染后委托：sectionHtml 仍在、升级失败 console.warn；渲染后 PixivNav.refresh + 加载模块 + 派发事件")
    void pageSectionKeepsFallbackAndDelegates() throws IOException {
        String js = read(MODULE);
        assertThat(js).as("必须保留命令式构造区块 HTML").contains("function sectionHtml(");
        String vue = sliceBetween(js, "function renderSectionsVue(", "function fetchSections(");
        assertThat(vue).as("PixivVue 缺失时不调用 ensure（调用方对未接管 slot 命令式兜底）").contains("!global.PixivVue");
        assertThat(vue).as("Vue 不可用 / 加载失败应收敛为 console.warn、不向宿主抛异常").contains("console.warn(");
        // renderAll：Vue 与命令式两路渲染完成后，统一委托内嵌导航 + 贡献方模块 + 生命周期事件。
        String render = sliceBetween(js, "async function renderAll(", "function markReady(");
        assertThat(render).as("渲染后委托 PixivNav.refresh 填内嵌导航 slot").contains("PixivNav.refresh()");
        assertThat(render).as("渲染后加载贡献方前端模块").contains("loadPendingModules()");
        assertThat(render).as("渲染后派发 pixivpagesections:rendered（贡献方据此填容器）").contains("dispatchRendered()");
        assertThat(render).as("未被 Vue 接管的 slot 走命令式 innerHTML（含失败清空）").contains("innerHTML");
    }

    @Test
    @DisplayName("运行时单一来源：区块渲染器不自带 / 不硬编码 /vendor/vue/")
    void pageSectionDoesNotBundleVueRuntime() throws IOException {
        String js = read(MODULE);
        assertThat(js).as("区块渲染器不得硬编码核心 Vue 运行时路径").doesNotContain("/vendor/vue/");
        assertThat(js).as("区块渲染器不得自带 Vue 全局构建版").doesNotContain("vue.global");
    }
}
