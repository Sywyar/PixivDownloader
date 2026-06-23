package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨插件导航渲染 Vue reactive 化的静态接线守卫：保证 reactive 主路径、命令式回退与运行时单一来源三者
 * 都不被后续改动悄悄破坏。纯静态资源内容断言（不启 Spring 上下文），与 {@link NovelSearchVueRenderingContractTest}
 * 同一形态（按 classpath 资源读取各模块 static/）。运行态行为另由 {@code src/test/js/pixiv-navigation.test.js} 验证。
 * <p>具体守住：
 * <ul>
 *   <li>导航渲染器 {@code pixiv-navigation.js} 的 slot 链接列表走 Vue（经 {@code PixivVue.ensure()} 懒加载核心运行时、
 *       {@code Vue.reactive} 共享状态、经统一 helper {@code PixivVue.mountOn} 把组件挂到 slot 元素），且保留命令式
 *       回退 {@code renderSlot} / {@code buildItemHtml}，挂载失败收敛为 {@code console.warn} + 不抛；</li>
 *   <li>每次渲染（含失败降级）后派发 {@code pixivnav:rendered} 生命周期事件、拉取失败清空 slot（与历史一致）；</li>
 *   <li>运行时单一来源：导航渲染器不自带 / 不硬编码 {@code /vendor/vue/} 或捆绑 Vue 文件（呼应「禁止自带共享前端运行时」红线）；</li>
 *   <li>渲染 Vue 化的全部宿主页（app 模块 6 页）都加载共享 helper {@code /js/pixiv-vue.js}（否则 Vue 路径静默回退命令式）。</li>
 * </ul>
 */
@DisplayName("Vue reactive 渲染契约守卫：跨插件导航 reactive 主路径 + 命令式回退 + 运行时单一来源")
class NavigationVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String NAV_MODULE = "js/pixiv-navigation.js";
    private static final String VUE_HELPER = "js/pixiv-vue.js";

    /** 加载导航渲染器、需经 Vue 主路径渲染其 nav slot 的 app 模块宿主页。 */
    private static final List<String> NAV_PAGES = List.of(
            "monitor.html", "pixiv-batch.html", "pixiv-gallery.html",
            "pixiv-novel-gallery.html", "pixiv-series.html", "pixiv-duplicates.html");

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = NavigationVueRenderingContractTest.class.getClassLoader().getResourceAsStream(path)) {
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
    @DisplayName("导航 slot 走 Vue reactive：经 PixivVue.ensure 懒加载运行时、Vue.reactive 共享状态、PixivVue.mountOn 挂组件")
    void navigationUsesVueReactivePath() throws IOException {
        String js = read(NAV_MODULE);
        assertThat(js).as("应经共享 helper 懒加载核心 Vue 运行时").contains("PixivVue.ensure(");
        assertThat(js).as("应用 Vue.reactive 建共享状态（导航项 + i18n）").contains("Vue.reactive(");
        assertThat(js).as("应经统一 helper mountOn 把 Vue 组件挂到 slot 元素（不自管 createApp/mount）").contains("PixivVue.mountOn(");
        assertThat(js).as("应有据 slot 构造 Vue 组件的工厂").contains("function buildSlotComponent(");
        assertThat(js).as("应有 Vue 导航项模板").contains("NAV_ITEM_TEMPLATE");
    }

    @Test
    @DisplayName("保留命令式回退：renderSlot/buildItemHtml 仍在，升级失败收敛为 console.warn 且不抛（优雅降级）")
    void navigationKeepsImperativeFallback() throws IOException {
        String js = read(NAV_MODULE);
        assertThat(js).as("必须保留命令式渲染单 slot（首屏占位 + 回退）").contains("function renderSlot(");
        assertThat(js).as("必须保留命令式构造导航项 HTML").contains("function buildItemHtml(");
        // 升级函数：PixivVue 缺失 → 直接回退；ensure / 挂载抛错 → catch 收敛为 console.warn + return false（不抛）。
        String upgrade = sliceBetween(js, "function upgradeSlotsToVue(", "function afterVueRender(");
        assertThat(upgrade).as("PixivVue 缺失时不调用 ensure（直接回退命令式）").contains("!global.PixivVue");
        assertThat(upgrade).as("Vue 不可用 / 加载失败应收敛为 console.warn、不向宿主抛异常").contains("console.warn(");
        assertThat(upgrade).as("升级失败返回 false（调用方保留命令式首屏）").contains("return false");
    }

    @Test
    @DisplayName("渲染生命周期与失败降级：每次渲染后派发 pixivnav:rendered；拉取失败清空 slot（不残留坏入口）")
    void navigationPreservesLifecycleAndDegradation() throws IOException {
        String js = read(NAV_MODULE);
        assertThat(js).as("每次渲染（含失败降级）后派发 pixivnav:rendered 生命周期事件").contains("pixivnav:rendered");
        String render = sliceBetween(js, "async function renderFromState(", "function dispatchRendered(");
        assertThat(render).as("拉取失败（items 为 null）应清空 slot").contains("state.items == null");
        assertThat(render).as("失败降级清空 slot 内容（不残留硬编码坏入口）").contains("innerHTML = ''");
    }

    @Test
    @DisplayName("运行时单一来源：导航渲染器不自带 / 不硬编码 /vendor/vue/")
    void navigationDoesNotBundleVueRuntime() throws IOException {
        String js = read(NAV_MODULE);
        assertThat(js).as("导航渲染器不得硬编码核心 Vue 运行时路径（只经 PixivVue helper 解析单一来源）").doesNotContain("/vendor/vue/");
        assertThat(js).as("导航渲染器不得自带 Vue 全局构建版").doesNotContain("vue.global");
    }

    @Test
    @DisplayName("渲染 Vue 化的 app 模块宿主页都加载共享 helper /js/pixiv-vue.js（否则 Vue 路径静默回退命令式）")
    void navPagesLoadVueHelper() throws IOException {
        // 共享 helper 自身存在（运行时单一来源经它 ensure）。
        assertThat(read(VUE_HELPER)).as("共享 Vue 挂载 helper 应存在").contains("PixivVue");
        for (String page : NAV_PAGES) {
            assertThat(read(page))
                    .as("页面 %s 必须加载 /js/pixiv-vue.js，导航 slot 才能据其挂 Vue 主渲染", page)
                    .contains("src=\"/js/pixiv-vue.js\"");
        }
    }
}
