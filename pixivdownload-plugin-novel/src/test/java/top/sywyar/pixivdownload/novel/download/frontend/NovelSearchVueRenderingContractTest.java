package top.sywyar.pixivdownload.novel.download.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/** 小说搜索模块的共享 Vue 运行时资源边界。 */
@DisplayName("小说搜索的 Vue 资源边界")
class NovelSearchVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String NOVEL_SEARCH_MODULE = "pixiv-novel-download/novel-queue-search.js";

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
