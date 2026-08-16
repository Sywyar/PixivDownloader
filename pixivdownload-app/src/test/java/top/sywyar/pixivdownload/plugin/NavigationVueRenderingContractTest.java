package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨插件导航渲染的静态装配守卫。Vue 主路径、命令式回退、失败降级与生命周期由
 * {@code src/test/js/pixiv-navigation.test.js} 执行真实脚本验证；这里仅保留无法由该运行态测试表达的资源边界。
 * <p>具体守住：
 * <ul>
 *   <li>运行时单一来源：导航渲染器不自带 / 不硬编码 {@code /vendor/vue/} 或捆绑 Vue 文件（呼应「禁止自带共享前端运行时」红线）；</li>
 *   <li>渲染 Vue 化的全部 app 内置宿主页都加载共享 helper {@code /js/pixiv-vue.js}（否则 Vue 路径静默回退命令式）。</li>
 * </ul>
 */
@DisplayName("跨插件导航 Vue 资源边界")
class NavigationVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String NAV_MODULE = "js/pixiv-navigation.js";
    private static final String VUE_HELPER = "js/pixiv-vue.js";
    private static final List<Path> STATIC_SOURCE_ROOTS = List.of(
            Path.of("src/main/resources/static"),
            Path.of("pixivdownload-app/src/main/resources/static"),
            Path.of("../pixivdownload-app/src/main/resources/static"),
            Path.of("pixivdownload-plugin-gallery/src/main/resources/static"),
            Path.of("../pixivdownload-plugin-gallery/src/main/resources/static"),
            Path.of("pixivdownload-plugin-novel/src/main/resources/static"),
            Path.of("../pixivdownload-plugin-novel/src/main/resources/static"));

    /** 加载导航渲染器、需经 Vue 主路径渲染其 nav slot 的 app 模块宿主页。 */
    private static final List<String> NAV_PAGES = List.of(
            "monitor.html", "pixiv-gallery.html",
            "pixiv-novel-gallery.html", "pixiv-series.html");

    private static String read(String resource) throws IOException {
        for (Path root : STATIC_SOURCE_ROOTS) {
            Path file = root.resolve(resource);
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        }
        String path = STATIC_ROOT + resource;
        try (InputStream in = NavigationVueRenderingContractTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new NoSuchFileException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
