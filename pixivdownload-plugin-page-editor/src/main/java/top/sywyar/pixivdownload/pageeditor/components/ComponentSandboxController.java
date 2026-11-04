package top.sywyar.pixivdownload.pageeditor.components;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 用户脚本只作为不透明 iframe 的外部脚本交付，永不进入宿主模块加载器。 */
@RestController
@RequestMapping("/page-components")
public final class ComponentSandboxController {
    private final ComponentLibrary library;
    private final ComponentPreviews previews;
    public ComponentSandboxController(ComponentLibrary library, ComponentPreviews previews) {
        this.library = library; this.previews = previews;
    }
    @GetMapping("/published/{id}/{version}/{asset}")
    public ResponseEntity<String> published(@PathVariable String id, @PathVariable String version,
            @PathVariable String asset, HttpServletRequest request) {
        if (asset.equals("status")) return ResponseEntity.status(library.enabled(id) ? 204 : 404).cacheControl(CacheControl.noStore()).build();
        return resource(library.published(id, version), asset, request);
    }
    @GetMapping("/previews/{token}/{asset}")
    public ResponseEntity<String> preview(@PathVariable String token, @PathVariable String asset, HttpServletRequest request) {
        if (asset.equals("status")) return ResponseEntity.status(previews.exists(token) ? 204 : 404).cacheControl(CacheControl.noStore()).build();
        return resource(previews.read(token), asset, request);
    }
    private ResponseEntity<String> resource(ObjectNode source, String asset, HttpServletRequest request) {
        String origin = ComponentLibraryController.origin(request);
        String path = request.getRequestURI();
        String base = origin + path.substring(0, path.lastIndexOf('/') + 1);
        String content, type;
        switch (asset) {
            case "frame" -> {
                type = "text/html;charset=UTF-8";
                content = """
                        <!doctype html><html><head><meta charset="utf-8">
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <link rel="stylesheet" href="style.css">
                        <script src="/page-components/frame.js" defer></script>
                        <script src="definition.js" defer></script>
                        </head><body><div id="root"></div></body></html>
                        """;
            }
            case "style.css" -> { type = "text/css;charset=UTF-8"; content = "html,body{margin:0;background:transparent}:where(body:not([hidden]),#root:not([hidden])){display:flow-root}\n" + source.path("css").asText(); }
            case "definition.js" -> {
                type = "text/javascript;charset=UTF-8";
                content = "window.componentDefinition = {html:" + source.path("html") + ",properties:" + source.path("properties")
                        + ",mount:function(root,context){\"use strict\";\n" + source.path("javascript").asText() + "\n}};";
            }
            default -> { return ResponseEntity.notFound().build(); }
        }
        String policy = "sandbox allow-scripts; default-src 'none'; base-uri 'none'; object-src 'none'; "
                + "script-src " + origin + "/page-components/frame.js " + base + "definition.js; script-src-attr 'none'; "
                + "style-src " + base + "style.css 'unsafe-inline'; img-src data:; connect-src 'none'; "
                + "frame-src 'none'; worker-src 'none'; form-action 'none'; frame-ancestors 'self'";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_TYPE, type)
                .header("Content-Security-Policy", policy).header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer").body(content);
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Void> unavailable(RuntimeException error) { return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).build(); }
}
