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
 *   <li>小说作品类型行为模块 {@code novel-queue-type.js} 的搜索网格走 Vue（经 {@code PixivVue.ensure()} 懒加载核心
 *       运行时 + 专属挂载根 {@code novel-search-vue-root} + {@code Vue.createApp().mount()}），且保留命令式回退
 *       {@code applyNovelSearchImperative} 与「{@code window.PixivVue} 缺失即回退」分支，descriptor 仍以
 *       {@code render} / {@code syncQueueState} 钩子接线；</li>
 *   <li>挂载失败不致空白：先命令式出图、再在游离根上 {@code createApp}/{@code mount} 成功后才替换搜索结果区，
 *       {@code PixivVue.ensure} resolve 后挂载抛错时命令式首屏结果保持完整、句柄置空、不向宿主抛异常；</li>
 *   <li>运行时单一来源：小说模块只经 helper 引用核心 Vue，不自带 / 不硬编码 {@code /vendor/vue/} 或捆绑 Vue 文件
 *       （呼应「外置插件禁止自带共享前端运行时」红线）；</li>
 * </ul>
 */
@DisplayName("Vue reactive 渲染契约守卫：小说搜索网格 reactive 路径 + 命令式回退 + 运行时单一来源")
class NovelSearchVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String NOVEL_MODULE = "pixiv-novel-download/novel-queue-type.js";

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = NovelSearchVueRenderingContractTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new NoSuchFileException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 截取 {@code source} 中 [startMarker, endMarker) 区间（不含 endMarker），把顺序断言限定在单个函数体内。 */
    private static String sliceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertThat(start).as("源码缺少起始标记: " + startMarker).isGreaterThanOrEqualTo(0);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(end).as("源码缺少结束标记: " + endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("小说搜索网格走 Vue reactive：经 PixivVue.ensure 懒加载运行时、专属挂载根、Vue.createApp 挂载")
    void novelSearchGridUsesVueReactivePath() throws IOException {
        String js = read(NOVEL_MODULE);
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
        String js = read(NOVEL_MODULE);
        assertThat(js)
                .as("必须保留命令式回退渲染函数")
                .contains("function applyNovelSearchImperative(");
        assertThat(js)
                .as("window.PixivVue 缺失时应优雅回退命令式（不依赖 Vue）")
                .contains("if (!window.PixivVue)");
        assertThat(js)
                .as("搜索取得侧仍以 render 钩子接线渲染器")
                .contains("render: renderNovelSearchResults");
        assertThat(js)
                .as("搜索取得侧仍以 syncQueueState 钩子接线队列态同步")
                .contains("syncQueueState: syncNovelSearchQueueState");
    }

    @Test
    @DisplayName("挂载失败保留命令式首屏：先命令式出图、Vue 在游离根挂载成功后才替换搜索结果区，createApp/mount 抛错不致空白")
    void vueMountFailureKeepsImperativeContent() throws IOException {
        String js = read(NOVEL_MODULE);
        // 渲染钩子：必须先命令式出图（写入 area）、再异步尝试挂载 Vue——这样 ensure resolve 后挂载抛错时 area 仍有内容。
        String render = sliceBetween(js,
                "function renderNovelSearchResults(", "function ensureNovelSearchMounted(");
        int imperativeAt = render.lastIndexOf("applyNovelSearchImperative(area, view)");
        int ensureCallAt = render.indexOf("ensureNovelSearchMounted(area)");
        assertThat(imperativeAt)
                .as("renderNovelSearchResults 应在尝试挂载 Vue 前先命令式渲染（首屏 / 回退占位）")
                .isGreaterThanOrEqualTo(0);
        assertThat(ensureCallAt)
                .as("命令式渲染必须先于异步挂载调用，挂载失败才能落在已渲染的命令式结果上")
                .isGreaterThan(imperativeAt);

        // 挂载钩子：createApp + mount 必须发生在「清空 / 替换 area」之前——
        // PixivVue.ensure resolve 后若 createApp/mount 抛错，此刻 area 未被清空，命令式首屏结果不空白。
        String mount = sliceBetween(js,
                "function ensureNovelSearchMounted(", "function buildNovelSearchModel(");
        int createAppAt = mount.indexOf("Vue.createApp(");
        int mountAt = mount.indexOf(".mount(root)");
        int clearAreaAt = mount.indexOf("area.innerHTML = ''");
        int appendRootAt = mount.indexOf("area.appendChild(root)");
        assertThat(createAppAt).as("应创建 Vue 应用").isGreaterThanOrEqualTo(0);
        assertThat(mountAt).as("应挂载到专属根节点").isGreaterThanOrEqualTo(0);
        assertThat(clearAreaAt).as("挂载成功后才清空 area").isGreaterThanOrEqualTo(0);
        assertThat(appendRootAt).as("挂载成功后才把 Vue 根挂入 area").isGreaterThanOrEqualTo(0);
        assertThat(mountAt)
                .as("必须先 mount 成功、再清空 area（否则 createApp/mount 抛错会留下空白搜索结果区）")
                .isLessThan(clearAreaAt);
        assertThat(mountAt)
                .as("必须先 mount 成功、再把 Vue 根挂入 area")
                .isLessThan(appendRootAt);
        // 失败分支：置空句柄、收敛为 console.warn、不向宿主抛异常。
        assertThat(mount)
                .as("createApp/mount 抛错时应把 _novelSearchVue 置空（不残留半挂载句柄）")
                .contains("_novelSearchVue = null");
        assertThat(mount)
                .as("挂载失败应收敛为 console.warn、不向宿主抛异常")
                .contains("console.warn(");
    }

    @Test
    @DisplayName("运行时单一来源：小说模块只经 helper 引用核心 Vue，不自带 / 不硬编码 /vendor/vue/")
    void novelModuleDoesNotBundleVueRuntime() throws IOException {
        String js = read(NOVEL_MODULE);
        assertThat(js)
                .as("小说行为模块不得硬编码核心 Vue 运行时路径（应只经 PixivVue helper 解析单一来源）")
                .doesNotContain("/vendor/vue/");
        assertThat(js)
                .as("小说行为模块不得自带 Vue 全局构建版（外置插件禁止自带共享前端运行时）")
                .doesNotContain("vue.global");
    }

}
