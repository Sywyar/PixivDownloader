package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 页面区块渲染的静态资源边界守卫。Vue 主路径、空子节点职责分离、命令式回退与渲染后委托由
 * {@code src/test/js/pixiv-page-sections.test.js} 执行真实脚本验证。
 */
@DisplayName("页面区块 Vue 资源边界")
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

    @Test
    @DisplayName("运行时单一来源：区块渲染器不自带 / 不硬编码 /vendor/vue/")
    void pageSectionDoesNotBundleVueRuntime() throws IOException {
        String js = read(MODULE);
        assertThat(js).as("区块渲染器不得硬编码核心 Vue 运行时路径").doesNotContain("/vendor/vue/");
        assertThat(js).as("区块渲染器不得自带 Vue 全局构建版").doesNotContain("vue.global");
    }
}
