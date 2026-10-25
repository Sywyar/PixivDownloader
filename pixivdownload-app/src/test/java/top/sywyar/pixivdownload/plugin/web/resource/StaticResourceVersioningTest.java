package top.sywyar.pixivdownload.plugin.web.resource;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("静态资源升级与缓存校验")
class StaticResourceVersioningTest {

    @TempDir
    Path directory;

    @Configuration
    @EnableWebMvc
    @Import({StaticResourceConfig.class, ResourcePageAdvice.class, PageController.class})
    static class ServingConfig {
        @Bean
        StaticResourceRegistry staticResourceRegistry() {
            StaticResourceRegistry registry = mock(StaticResourceRegistry.class);
            when(registry.resources()).thenReturn(List.of());
            return registry;
        }
    }

    @RestController
    static class PageController {
        @GetMapping("/controlled.html")
        ResponseEntity<?> page() {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML)
                    .body(new ClassPathResource("static/login.html"));
        }

        @GetMapping("/attachment.html")
        ResponseEntity<?> attachment() {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                    .header("Content-Disposition", "attachment; filename=login.html")
                    .body(new ClassPathResource("static/login.html"));
        }
    }

    @Test
    @DisplayName("控制器直接返回的页面资源也使用共享版本处理，保留原缓存限制")
    void controllerResourcesReceiveVersionedDependencies() throws Exception {
        try (var context = context()) {
            var response = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/controlled.html"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse();
            var html = Jsoup.parse(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(html.select("script[data-pixiv-assets]")).hasSize(1);
            assertThat(html.selectFirst("script[src*=pixiv-i18n.js]").attr("src")).contains("_asset=");
            assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
            var attachment = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/attachment.html"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(attachment.getContentAsByteArray())
                    .isEqualTo(new ClassPathResource("static/login.html").getContentAsByteArray());
        }
    }

    private AnnotationConfigWebApplicationContext context() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ServingConfig.class);
        context.refresh();
        return context;
    }

    private StaticResourceRegistry.RegisteredStaticResource contribution(String prefix, boolean exact) {
        return new StaticResourceRegistry.RegisteredStaticResource(
                mock(PluginRegistry.RegisteredPlugin.class),
                new StaticResourceContribution("classpath:/static/", prefix, exact),
                new FileSystemResource(directory.toString() + "/"));
    }

    @Test
    @DisplayName("核心页面与插件脚本使用内容校验，旧修改时间不能让浏览器保留旧文件")
    void coreAndContributedResourcesUseContentValidation() throws Exception {
        Files.writeString(directory.resolve("app.js"), "window.value = 'before';", StandardCharsets.UTF_8);
        try (var context = context()) {
            var registry = context.getBean(StaticResourceRegistry.class);
            when(registry.resources()).thenReturn(List.of(contribution("/demo/", false)));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            var page = mvc.perform(get("/login.html")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(header().doesNotExist("Last-Modified")).andReturn().getResponse();
            assertThat(Jsoup.parse(page.getContentAsString(StandardCharsets.UTF_8))
                    .selectFirst("script[src*=pixiv-i18n.js]").attr("src"))
                    .containsPattern("[?&]_asset=[0-9a-f]{64}");
            var first = mvc.perform(get("/demo/app.js")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(header().doesNotExist("Last-Modified")).andReturn().getResponse();
            String etag = first.getHeader("ETag");
            assertThat(etag).matches("\"[0-9a-f]{64}\"");
            mvc.perform(get("/demo/app.js").header("If-None-Match", etag)).andExpect(status().isNotModified());
            mvc.perform(get("/demo/app.js").header("If-Modified-Since", "Wed, 01 Jan 2031 00:00:00 GMT"))
                    .andExpect(status().isOk());
            mvc.perform(head("/demo/app.js")).andExpect(status().isOk())
                    .andExpect(header().string("ETag", etag))
                    .andExpect(header().longValue("Content-Length", Files.size(directory.resolve("app.js"))));
            mvc.perform(get("/demo/app.js").header("Range", "bytes=0-5"))
                    .andExpect(status().isPartialContent());
            mvc.perform(get("/demo/missing.js")).andExpect(status().isNotFound());
            mvc.perform(get("/demo/../application.properties")).andExpect(status().is4xxClientError());
        }
    }

    @Test
    @DisplayName("脚本内容变化但大小与时间相同时，页面摘要和依赖 URL 仍更新")
    void equalTimestampAndSizeCannotKeepOldScriptOrHtml() throws Exception {
        Path script = directory.resolve("app.js");
        Files.writeString(script, "window.value = 'before';", StandardCharsets.UTF_8);
        FileTime time = Files.getLastModifiedTime(script);
        Files.writeString(directory.resolve("upgrade.html"),
                "<script src='/demo/app.js?keep=1#fragment'></script>", StandardCharsets.UTF_8);
        try (var context = context()) {
            when(context.getBean(StaticResourceRegistry.class).resources()).thenReturn(List.of(
                    contribution("/demo/", false), contribution("/upgrade.html", true)));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            var first = mvc.perform(get("/upgrade.html")).andExpect(status().isOk()).andReturn().getResponse();
            String scriptEtag = mvc.perform(get("/demo/app.js")).andExpect(status().isOk())
                    .andReturn().getResponse().getHeader("ETag");
            String firstUrl = Jsoup.parse(first.getContentAsString()).selectFirst("script[src^=/demo/]").attr("src");
            assertThat(firstUrl).containsPattern("/demo/app.js\\?keep=1&_asset=[0-9a-f]{64}#fragment");
            mvc.perform(get("/upgrade.html").header("If-None-Match", first.getHeader("ETag")))
                    .andExpect(status().isNotModified());
            Files.writeString(script, "window.value = 'after!';", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(script, time);
            var updatedScript = mvc.perform(get("/demo/app.js").header("If-None-Match", scriptEtag))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(updatedScript.getHeader("ETag")).isNotEqualTo(scriptEtag);
            assertThat(updatedScript.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("window.value = 'after!';");
            var second = mvc.perform(get("/upgrade.html").header("If-None-Match", first.getHeader("ETag")))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(second.getHeader("ETag")).isNotEqualTo(first.getHeader("ETag"));
            assertThat(Jsoup.parse(second.getContentAsString()).selectFirst("script[src^=/demo/]").attr("src")).isNotEqualTo(firstUrl);
            when(context.getBean(StaticResourceRegistry.class).resources()).thenReturn(List.of());
            mvc.perform(get("/upgrade.html")).andExpect(status().isNotFound());
            mvc.perform(get(firstUrl.substring(0, firstUrl.indexOf('#')))).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("保留页面字节与外链，正确解析子路径、base、查询参数及样式预加载")
    void preservesMarkupAndResolvesLocalDependencies() throws Exception {
        Files.writeString(directory.resolve("app.js"), "window.loaded = true;", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("app.css"), "body { color: red; }", StandardCharsets.UTF_8);
        String html = """
                <!doctype html>
                <base href="/app/demo/">
                <!-- <script src="comment.js"></script> -->
                <script>const template = '<script src="inline.js">';</script>
                <script src='app.js?x=1&amp;y=2#part'></script>
                <script src=app.js></script>
                <link rel="stylesheet" href="app.css">
                <link rel="PRELOAD" as="STYLE" href="app.css">
                <link rel="modulepreload" href="app.js">
                <link rel="icon" href="app.js">
                <script src="https://example.invalid/app.js"></script>
                <script src="//example.invalid/app.js"></script>
                <script src="missing.js"></script>
                """;
        Files.writeString(directory.resolve("page.html"), html, StandardCharsets.UTF_8);
        try (var context = context()) {
            when(context.getBean(StaticResourceRegistry.class).resources())
                    .thenReturn(List.of(contribution("/demo/", false)));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            var response = mvc.perform(get("/app/demo/page.html").contextPath("/app"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            String output = response.getContentAsString(StandardCharsets.UTF_8)
                    .replaceFirst("<script[^>]+data-pixiv-assets=\"[^\"]+\"></script>", "");
            var document = Jsoup.parse(output);
            assertThat(document.select("script[src]").get(0).attr("src"))
                    .matches("app.js\\?x=1&y=2&_asset=[0-9a-f]{64}#part");
            assertThat(document.select("script[src]").get(1).attr("src"))
                    .matches("app.js\\?_asset=[0-9a-f]{64}");
            assertThat(document.select("link").subList(0, 3))
                    .allSatisfy(link -> assertThat(link.attr("href")).contains("_asset="));
            assertThat(document.selectFirst("link[rel=icon]").attr("href")).isEqualTo("app.js");
            assertThat(document.select("script[src]").subList(2, 5))
                    .allSatisfy(script -> assertThat(script.attr("src")).doesNotContain("_asset="));
            assertThat(output.replaceAll("(?:\\?|&amp;)_asset=[0-9a-f]{64}", "")).isEqualTo(html);
        }
    }

    @Test
    @DisplayName("CSS 的 import、字体与图片引用更新，外链、data 和片段引用保留")
    void cssDependenciesUseContentVersions() throws Exception {
        Files.writeString(directory.resolve("main.css"),
                "@import 'nested.css'; .a{background:url(image.svg?x=1#icon)}"
                        + ".b{filter:url(#mask)} .c{background:url(data:image/png;base64,AAAA)}"
                        + ".d{background:url(https://example.invalid/image.png)}", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("nested.css"),
                "@font-face{src:url('font.woff2')}", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("image.svg"), "<svg/>", StandardCharsets.UTF_8);
        Path font = directory.resolve("font.woff2");
        Files.write(font, new byte[]{1, 2, 3});
        try (var context = context()) {
            when(context.getBean(StaticResourceRegistry.class).resources())
                    .thenReturn(List.of(contribution("/demo/", false)));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String css = mvc.perform(get("/demo/main.css")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(css).containsPattern("nested.css\\?_asset=[0-9a-f]{64}")
                    .containsPattern("image.svg\\?x=1&_asset=[0-9a-f]{64}#icon")
                    .contains("url(#mask)", "url(data:image/png;base64,AAAA)",
                            "url(https://example.invalid/image.png)");
            var first = mvc.perform(get("/demo/nested.css")).andExpect(status().isOk())
                    .andReturn().getResponse();
            assertThat(first.getContentAsString(StandardCharsets.UTF_8))
                    .containsPattern("font.woff2\\?_asset=[0-9a-f]{64}");
            Files.write(font, new byte[]{3, 2, 1});
            var changed = mvc.perform(get("/demo/nested.css").header("If-None-Match", first.getHeader("ETag")))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(changed.getHeader("ETag")).isNotEqualTo(first.getHeader("ETag"));
        }
    }

    @Test
    @DisplayName("动态资源引导先于页面脚本，同次服务 URL 稳定，重启生成新身份")
    void bootstrapIsStableUntilRestart() throws Exception {
        String previous = null;
        for (int restart = 0; restart < 2; restart++) {
            try (var context = context()) {
                MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
                var first = mvc.perform(get("/login.html")).andExpect(status().isOk()).andReturn().getResponse();
                var html = Jsoup.parse(first.getContentAsString(StandardCharsets.UTF_8));
                var bootstrap = html.selectFirst("script");
                String token = bootstrap.attr("data-pixiv-assets");
                assertThat(token).isNotBlank().isNotEqualTo(previous);
                assertThat(bootstrap.attr("src")).startsWith("/js/pixiv-assets.js?_asset=");
                mvc.perform(get("/login.html").header("If-None-Match", first.getHeader("ETag")))
                        .andExpect(status().isNotModified());
                previous = token;
            }
        }
    }
}
