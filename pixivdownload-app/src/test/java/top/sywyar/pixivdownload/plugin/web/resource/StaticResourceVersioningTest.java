package top.sywyar.pixivdownload.plugin.web.resource;

import tools.jackson.databind.json.JsonMapper;
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
import top.sywyar.pixivdownload.plugin.api.web.page.EditablePage;
import top.sywyar.pixivdownload.plugin.api.web.page.PageCatalog;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;

import top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePublications;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("静态资源升级与缓存校验")
class StaticResourceVersioningTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("静态语言元数据仅在开发模式按访问端口隔离，缓存不能复用其它端口的名称")
    void languageMetadataCookieScopeAndCache() throws Exception {
        String property = top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts.ENABLED_PROPERTY;
        String previous = System.getProperty(property);
        try (var context = context()) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            byte[] original = new ClassPathResource("static/i18n-static/meta.json").getContentAsByteArray();
            String previousEtag = null;
            for (String mode : new String[]{null, "false", "true"}) {
                if (mode == null) System.clearProperty(property);
                else System.setProperty(property, mode);
                for (int port : new int[]{80, 8081}) {
                    var result = mvc.perform(get("http://localhost:" + port + "/i18n-static/meta.json"))
                            .andExpect(status().isOk()).andReturn().getResponse();
                    if (!"true".equals(mode)) {
                        assertThat(result.getContentAsByteArray()).isEqualTo(original);
                    } else {
                        var metadata = JsonMapper.builderWithJackson2Defaults().build().readTree(result.getContentAsByteArray());
                        assertThat(metadata.get("languageCookieName").asText()).isEqualTo("pixiv_dev_" + port + "_pixiv_lang");
                        assertThat(result.getHeader("ETag")).isNotEqualTo(previousEtag);
                        mvc.perform(get("http://localhost:" + port + "/i18n-static/meta.json")
                                        .header("If-None-Match", previousEtag))
                                .andExpect(status().isOk());
                    }
                    previousEtag = result.getHeader("ETag");
                }
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({StaticResourceConfig.class, ResourcePageAdvice.class, PageController.class})
    static class ServingConfig {
        @Bean PagePublicationRequest pagePublicationRequest() { return mock(PagePublicationRequest.class); }
        @Bean
        PageContributionRegistry pageContributionRegistry() {
            return mock(PageContributionRegistry.class);
        }

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

    private PageCatalog.AvailablePage page(String id, String path, boolean publicPage) {
        return new PageCatalog.AvailablePage(new EditablePage(id, "page.title", "example", path,
                publicPage, List.of(), "", "", List.of(), List.of()), "publication");
    }

    private PagePublications.Published publication(String pageId, String title) throws Exception {
        return published(JsonMapper.builderWithJackson2Defaults().build().createObjectNode()
                .put("pageId", pageId).put("title", title).toString());
    }
    private PagePublications.Published published(String projection) {
        return new PagePublications.Published(1, "{}", projection, "");
    }

    @Test
    @DisplayName("首次 HTML 包含已发布标题，重新发布与撤回立即改变条件请求结果")
    void publishedTitleParticipatesInContentValidation() throws Exception {
        try (var context = context()) {
            var pages = context.getBean(PageContributionRegistry.class);
            var publications = context.getBean(PagePublicationRequest.class);
            when(pages.pages()).thenReturn(List.of(page("example:login", "/login.html", true)));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            var original = mvc.perform(get("/login.html")).andReturn().getResponse();
            String title = "备案 </title><script>alert(1)</script> & \"site\"";
            when(publications.read(eq("example:login"), any())).thenReturn(Optional.of(publication("example:login", title)));
            var published = mvc.perform(get("/login.html").header("If-None-Match", original.getHeader("ETag")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-cache"))
                    .andReturn().getResponse();
            var html = Jsoup.parse(published.getContentAsString(StandardCharsets.UTF_8));
            assertThat(html.title()).isEqualTo(title);
            assertThat(html.select("title[data-pixiv-page-title]")).hasSize(1);
            assertThat(html.select("script:not([src])")).isEmpty();
            mvc.perform(get("/login.html").header("If-None-Match", published.getHeader("ETag")))
                    .andExpect(status().isNotModified());
            mvc.perform(head("/login.html")).andExpect(status().isOk())
                    .andExpect(header().string("ETag", published.getHeader("ETag")));
            when(publications.read(eq("example:login"), any())).thenReturn(Optional.of(publication("example:login", "Updated title")));
            var changed = mvc.perform(get("/login.html").header("If-None-Match", published.getHeader("ETag")))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(Jsoup.parse(changed.getContentAsString(StandardCharsets.UTF_8)).title()).isEqualTo("Updated title");
            when(publications.read(eq("example:login"), any())).thenReturn(Optional.empty());
            var withdrawn = mvc.perform(get("/login.html").header("If-None-Match", changed.getHeader("ETag")))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(withdrawn.getContentAsByteArray()).isEqualTo(original.getContentAsByteArray());
            assertThat(withdrawn.getHeader("ETag")).isEqualTo(original.getHeader("ETag"));
        }
    }

    @Test
    @DisplayName("声明的插件页面与控制器页面共享标题处理，保留子路径和原有缓存限制")
    void titleResolutionUsesDeclaredPagePaths() throws Exception {
        Files.writeString(directory.resolve("page.html"), "<title id='page-name'></title><p>Content</p>", StandardCharsets.UTF_8);
        try (var context = context()) {
            when(context.getBean(StaticResourceRegistry.class).resources()).thenReturn(List.of(contribution("/demo/", false)));
            var pages = context.getBean(PageContributionRegistry.class);
            var publications = context.getBean(PagePublicationRequest.class);
            when(pages.pages()).thenReturn(List.of(page("example:custom", "/demo/page.html", true),
                    page("example:controlled", "/controlled.html", false)));
            when(publications.read(eq("example:custom"), any())).thenReturn(Optional.of(publication("example:custom", "Custom page")));
            when(publications.read(eq("example:controlled"), any())).thenReturn(Optional.of(publication("example:controlled", "Controlled page")));
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            var custom = mvc.perform(get("/app/demo/page.html?redirect=/other").contextPath("/app"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            var html = Jsoup.parse(custom.getContentAsString(StandardCharsets.UTF_8));
            assertThat(html.title()).isEqualTo("Custom page");
            assertThat(html.selectFirst("title").id()).isEqualTo("page-name");
            var controlled = mvc.perform(get("/controlled.html")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
            assertThat(Jsoup.parse(controlled.getContentAsString(StandardCharsets.UTF_8)).title()).isEqualTo("Controlled page");
        }
    }

    @Test
    @DisplayName("无效投影和来源失败保留默认页面及标题")
    void unavailableOrInvalidPublicationKeepsDefaultTitle() throws Exception {
        try (var context = context()) {
            var pages = context.getBean(PageContributionRegistry.class);
            var publications = context.getBean(PagePublicationRequest.class);
            when(pages.pages()).thenReturn(List.of(page("example:login", "/login.html", true)));
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            byte[] original = mvc.perform(get("/login.html")).andReturn().getResponse().getContentAsByteArray();
            for (String value : List.of("null", "{", "{}", publication("other:page", "Private title").projection(),
                    publication("example:login", "").projection(), publication("example:login", "x".repeat(257)).projection(),
                    "{\"pageId\":\"example:login\",\"title\":42}")) {
                when(publications.read(eq("example:login"), any())).thenReturn(Optional.of(published(value)));
                assertThat(mvc.perform(get("/login.html")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsByteArray()).isEqualTo(original);
            }
            when(publications.read(eq("example:login"), any())).thenThrow(new IllegalStateException("PAGE_STORAGE_FAILED"));
            assertThat(mvc.perform(get("/login.html")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray()).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("未声明与路径歧义不读取发布内容，私有 HTML 的鉴权由原入口负责")
    void onlyUniqueDeclaredPageCanSupplyTitle() throws Exception {
        try (var context = context()) {
            var pages = context.getBean(PageContributionRegistry.class);
            var publications = context.getBean(PagePublicationRequest.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (var declarations : List.of(List.<PageCatalog.AvailablePage>of(),
                    List.of(page("example:first", "/login.html", true), page("example:second", "/login.html", true)))) {
                when(pages.pages()).thenReturn(declarations);
                var response = mvc.perform(get("/login.html")).andExpect(status().isOk()).andReturn().getResponse();
                assertThat(Jsoup.parse(response.getContentAsString(StandardCharsets.UTF_8))
                        .select("title[data-pixiv-page-title]")).isEmpty();
            }
            verify(publications, never()).read(anyString(), any());
        }
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
