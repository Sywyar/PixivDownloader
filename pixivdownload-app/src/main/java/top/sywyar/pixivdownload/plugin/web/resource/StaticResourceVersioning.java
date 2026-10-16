package top.sywyar.pixivdownload.plugin.web.resource;

import jakarta.servlet.http.HttpServletRequest;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceHandlerUtils;
import org.springframework.web.servlet.resource.CssLinkResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;
import org.springframework.web.util.HtmlUtils;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 按实际内容校验静态资源，更新页面与样式的依赖 URL；不缓存资源或插件 owner。 */
final class StaticResourceVersioning extends CssLinkResourceTransformer {

    static final Resource CORE_LOCATION = new ClassPathResource("static/");
    private final StaticResourceRegistry registry;
    private final PathResourceResolver resolver = new PathResourceResolver();
    private final String runtimeVersion = UUID.randomUUID().toString();

    StaticResourceVersioning(StaticResourceRegistry registry) {
        this.registry = registry;
    }

    static String contentHash(Resource resource) {
        try (var input = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    @Override
    public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
            throws IOException {
        return transformPage(request, super.transform(request, resource, chain));
    }

    Resource transformPage(HttpServletRequest request, Resource transformed) throws IOException {
        String filename = transformed.getFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".html")) {
            return transformed;
        }
        String html = transformed.getContentAsString(StandardCharsets.UTF_8);
        var document = Parser.htmlParser().setTrackPosition(true).parseInput(html, "");
        URI base = URI.create(request.getRequestURI());
        Element baseElement = document.selectFirst("base[href]");
        if (baseElement != null) {
            try {
                base = base.resolve(baseElement.attr("href"));
            } catch (IllegalArgumentException ignored) {
                return transformed;
            }
        }
        List<Replacement> replacements = new ArrayList<>();
        Element firstScript = document.selectFirst("script");
        if (firstScript != null && firstScript.sourceRange().isTracked()) {
            String helper = request.getContextPath() + "/js/pixiv-assets.js";
            String source = versionedUrl(request, URI.create(request.getRequestURI()), helper);
            int position = firstScript.sourceRange().startPos();
            replacements.add(new Replacement(position, position,
                    "<script src=\"" + HtmlUtils.htmlEscape(source) + "\" data-pixiv-assets=\""
                            + runtimeVersion + "\"></script>"));
        }
        for (Element element : document.select("script[src], link[href]")) {
            String attribute = "script".equals(element.normalName()) ? "src" : "href";
            if ("href".equals(attribute) && !isStylesheetOrScript(element)) continue;
            String original = element.attr(attribute);
            String versioned = versionedUrl(request, base, original);
            Range range = element.attributes().sourceRange(attribute).valueRange();
            if (!original.equals(versioned) && range.isTracked()) {
                replacements.add(new Replacement(range.startPos(), range.endPos(), HtmlUtils.htmlEscape(versioned)));
            }
        }
        if (replacements.isEmpty()) return transformed;
        // 引导脚本先于页面脚本；其余只替换属性值，保留内联脚本、模板与原有字节。
        StringBuilder output = new StringBuilder(html);
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (Replacement replacement : replacements) {
            output.replace(replacement.start(), replacement.end(), replacement.value());
        }
        return new TransformedResource(transformed, output.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected String toAbsolutePath(String path, HttpServletRequest request) {
        return path;
    }

    @Override
    protected String resolveUrlPath(String path, HttpServletRequest request,
                                    Resource resource, ResourceTransformerChain chain) {
        return versionedUrl(request, URI.create(request.getRequestURI()), path);
    }

    private static boolean isStylesheetOrScript(Element element) {
        List<String> relations = List.of(element.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"));
        return relations.contains("stylesheet") || relations.contains("modulepreload")
                || relations.contains("preload")
                && List.of("script", "style").contains(element.attr("as").toLowerCase(Locale.ROOT));
    }

    private String versionedUrl(HttpServletRequest request, URI base, String original) {
        URI target;
        try {
            if (original.isBlank() || original.startsWith("#")) return original;
            target = base.resolve(original).normalize();
        } catch (IllegalArgumentException invalidUrl) {
            return original;
        }
        if (target.isAbsolute() || target.getRawAuthority() != null) return original;
        String path = target.getPath();
        String contextPath = request.getContextPath();
        if (!path.startsWith(contextPath + "/")) return original;
        path = path.substring(contextPath.length());
        if (ResourceHandlerUtils.shouldIgnoreInputPath(path)) return original;
        Resource dependency = resolve(request, path);
        if (dependency == null) return original;
        int fragmentIndex = original.indexOf('#');
        String fragment = fragmentIndex < 0 ? "" : original.substring(fragmentIndex);
        String url = fragmentIndex < 0 ? original : original.substring(0, fragmentIndex);
        return url + (url.contains("?") ? "&" : "?") + "_asset=" + contentHash(dependency) + fragment;
    }

    private Resource resolve(HttpServletRequest request, String path) {
        StaticResourceRegistry.RegisteredStaticResource selected = null;
        for (var resource : registry.resources()) {
            var contribution = resource.contribution();
            String prefix = contribution.publicPathPrefix();
            if ((contribution.exactFile() ? path.equals(prefix) : path.startsWith(prefix))
                    && (selected == null || prefix.length() > selected.contribution().publicPathPrefix().length())) {
                selected = resource;
            }
        }
        Resource location = selected == null ? CORE_LOCATION : selected.location();
        String relative = selected == null ? path.substring(1)
                : selected.contribution().exactFile() ? path.substring(path.lastIndexOf('/') + 1)
                : path.substring(selected.contribution().publicPathPrefix().length());
        return resolver.resolveResource(request, relative, List.of(location), null);
    }

    private record Replacement(int start, int end, String value) {}
}
