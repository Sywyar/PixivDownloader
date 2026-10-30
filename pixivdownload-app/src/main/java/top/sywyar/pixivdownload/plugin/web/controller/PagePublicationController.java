package top.sywyar.pixivdownload.plugin.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;

/** 公开面仅返回 owner 已声明为公开的已发布投影。 */
@RestController
public final class PagePublicationController {
    private final PageContributionRegistry registry;
    private final ObjectMapper json = new ObjectMapper();
    public PagePublicationController(PageContributionRegistry registry) { this.registry = registry; }
    @GetMapping("/api/web/page-publications/{page}")
    public ResponseEntity<JsonNode> published(@PathVariable String page) throws java.io.IOException {
        if (registry.pages().stream().noneMatch(p -> p.definition().id().equals(page) && p.definition().publicPage())) return ResponseEntity.notFound().build();
        java.util.Optional<String> value;
        try { value = registry.published(page); }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
        if (value.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(json.readTree(value.orElseThrow()));
    }
}
