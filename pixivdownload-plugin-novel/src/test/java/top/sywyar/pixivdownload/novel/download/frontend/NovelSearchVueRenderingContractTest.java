package top.sywyar.pixivdownload.novel.download.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小说搜索结果网格 Vue reactive 渲染的静态接线守卫：保证 reactive 路径、命令式回退与运行时单一来源
 * 三者都不被改动悄悄破坏。纯静态资源内容断言（不启 Spring 上下文），与 {@code NavigationMarkupGuardTest}
 * 同一「按 classpath 资源读取各模块 static/」的形态。
 * <p>具体守住：
 * <ul>
 *   <li>小说作品类型搜索模块 {@code novel-queue-search.js} 的搜索网格走 Vue（经 {@code PixivVue.ensure()} 懒加载核心
 *       运行时 + 专属挂载根 {@code novel-search-vue-root} + {@code Vue.createApp().mount()}），且保留命令式回退
 *       {@code applyNovelSearchImperative} 与「{@code window.PixivVue} 缺失即回退」分支，descriptor 仍以
 *       {@code render} / {@code syncQueueState} 钩子接线；</li>
 *   <li>运行时单一来源：小说模块只经 helper 引用核心 Vue，不自带 / 不硬编码 {@code /vendor/vue/} 或捆绑 Vue 文件
 *       （呼应「外置插件禁止自带共享前端运行时」红线）；</li>
 * </ul>
 */
@DisplayName("Vue reactive 渲染契约守卫：小说搜索网格 reactive 路径 + 命令式回退 + 运行时单一来源")
class NovelSearchVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String NOVEL_SEARCH_MODULE = "pixiv-novel-download/novel-queue-search.js";
    private static final String NOVEL_ACQUISITION_MODULE = "pixiv-novel-download/novel-queue-acquisition.js";

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = NovelSearchVueRenderingContractTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new NoSuchFileException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("小说搜索网格走 Vue reactive：经 PixivVue.ensure 懒加载运行时、专属挂载根、Vue.createApp 挂载")
    void novelSearchGridUsesVueReactivePath() throws IOException {
        String js = read(NOVEL_SEARCH_MODULE);
        assertThat(js)
                .as("搜索网格应经共享 helper 懒加载核心 Vue 运行时")
                .contains("PixivVue.ensure(");
        assertThat(js)
                .as("Vue 应挂到专属根节点（与宿主命令式写 area.innerHTML 隔离、便于探测重挂）")
                .contains("novel-search-vue-root");
        assertThat(js)
                .as("应真实创建并挂载 Vue 应用")
                .contains("Vue.createApp(");
    }

    @Test
    @DisplayName("保留命令式回退：Vue 不可用即回退 applyNovelSearchImperative，且 descriptor 仍以 render/syncQueueState 接线")
    void novelSearchGridKeepsImperativeFallback() throws IOException {
        String js = read(NOVEL_SEARCH_MODULE);
        String acquisition = read(NOVEL_ACQUISITION_MODULE);
        assertThat(js)
                .as("必须保留命令式回退渲染函数")
                .contains("function applyNovelSearchImperative(");
        assertThat(js)
                .as("window.PixivVue 缺失时应优雅回退命令式（不依赖 Vue）")
                .contains("if (!window.PixivVue)");
        assertThat(acquisition)
                .as("搜索取得侧仍以 render 钩子接线渲染器")
                .contains("render: renderNovelSearchResults");
        assertThat(acquisition)
                .as("搜索取得侧仍以 syncQueueState 钩子接线队列态同步")
                .contains("syncQueueState: syncNovelSearchQueueState");
    }

    @Test
    @DisplayName("运行时单一来源：小说模块只经 helper 引用核心 Vue，不自带 / 不硬编码 /vendor/vue/")
    void novelModuleDoesNotBundleVueRuntime() throws IOException {
        String js = read(NOVEL_SEARCH_MODULE);
        assertThat(js)
                .as("小说行为模块不得硬编码核心 Vue 运行时路径（应只经 PixivVue helper 解析单一来源）")
                .doesNotContain("/vendor/vue/");
        assertThat(js)
                .as("小说行为模块不得自带 Vue 全局构建版（外置插件禁止自带共享前端运行时）")
                .doesNotContain("vue.global");
    }

}
