package top.sywyar.pixivdownload.novel.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.novel.translation.NovelGlossaryService;
import top.sywyar.pixivdownload.novel.db.NovelGlossary;
import top.sywyar.pixivdownload.novel.db.NovelGlossaryEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 名词映射表（glossary）管理端点。挂在 {@code /api/admin/} 前缀下：{@code AuthFilter} 按 monitor 语义保护，
 * 且不在任何访客邀请白名单（GUEST_ALLOWED_*）中 —— 因此 solo / multi 两种模式下都<b>仅管理员可用</b>，
 * 访客（含邀请访客）无法读取/修改映射表（与 {@code /api/gallery/} 的 GET 对访客开放不同）。
 */
@RestController
@RequestMapping("/api/admin/glossary")
@RequiredArgsConstructor
public class NovelGlossaryController {

    private final NovelGlossaryService glossaryService;

    @GetMapping
    public GlossaryListResponse list() {
        List<GlossaryView> views = glossaryService.listAll().stream()
                .map(NovelGlossaryController::toView)
                .toList();
        return new GlossaryListResponse(views);
    }

    @PostMapping
    public ResponseEntity<GlossaryView> create(@RequestBody CreateGlossaryRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        NovelGlossary created = glossaryService.create(
                request.name(), request.seriesId(), request.novelId());
        return ResponseEntity.ok(toView(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GlossaryDetail> get(@PathVariable long id) {
        NovelGlossary glossary = glossaryService.find(id);
        if (glossary == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDetail(glossary, glossaryService.entries(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<GlossaryView> rename(@PathVariable long id,
                                               @RequestBody RenameRequest request) {
        if (!glossaryService.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(toView(glossaryService.rename(id, request.name())));
    }

    @PutMapping("/{id}/entries")
    public ResponseEntity<GlossaryDetail> replaceEntries(@PathVariable long id,
                                                         @RequestBody EntriesRequest request) {
        if (!glossaryService.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        List<NovelGlossaryEntry> entries = new ArrayList<>();
        if (request != null && request.entries() != null) {
            for (EntryDto e : request.entries()) {
                if (e == null) continue;
                entries.add(new NovelGlossaryEntry(e.source(), e.lang(), e.target()));
            }
        }
        glossaryService.replaceEntries(id, entries);
        NovelGlossary glossary = glossaryService.find(id);
        return ResponseEntity.ok(toDetail(glossary, glossaryService.entries(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!glossaryService.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        glossaryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/novel/{novelId}/default")
    public ResponseEntity<DefaultGlossaryResponse> novelDefault(@PathVariable long novelId) {
        NovelGlossaryService.DefaultGlossary def = glossaryService.resolveNovelDefault(novelId);
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDefaultResponse(def));
    }

    @GetMapping("/series/{seriesId}/default")
    public ResponseEntity<DefaultGlossaryResponse> seriesDefault(@PathVariable long seriesId) {
        return ResponseEntity.ok(toDefaultResponse(glossaryService.resolveSeriesDefault(seriesId)));
    }

    private static GlossaryView toView(NovelGlossary g) {
        return new GlossaryView(g.id(), g.name(), g.seriesId(), g.novelId(),
                g.entryCount(), g.updatedTime());
    }

    private static GlossaryDetail toDetail(NovelGlossary g, List<NovelGlossaryEntry> entries) {
        List<EntryDto> dtos = entries.stream()
                .map(e -> new EntryDto(e.source(), e.langCode(), e.target()))
                .toList();
        return new GlossaryDetail(g.id(), g.name(), g.seriesId(), g.novelId(), dtos);
    }

    private static DefaultGlossaryResponse toDefaultResponse(NovelGlossaryService.DefaultGlossary def) {
        NovelGlossary g = def.glossary();
        if (g != null) {
            return new DefaultGlossaryResponse(g.id(), g.name(), g.seriesId(), g.novelId(), g.entryCount());
        }
        return new DefaultGlossaryResponse(null, def.suggestedName(),
                def.seriesId(), def.novelId(), 0);
    }

    public record GlossaryListResponse(List<GlossaryView> glossaries) {}

    public record GlossaryView(long id, String name, Long seriesId, Long novelId,
                               int entryCount, long updatedTime) {}

    public record GlossaryDetail(long id, String name, Long seriesId, Long novelId,
                                 List<EntryDto> entries) {}

    public record EntryDto(String source, String lang, String target) {}

    public record CreateGlossaryRequest(String name, Long seriesId, Long novelId) {}

    public record RenameRequest(String name) {}

    public record EntriesRequest(List<EntryDto> entries) {}

    /** {@code id} 为 {@code null} 表示该作品的默认映射表尚未创建，前端可据 {@code name} + 绑定按需创建。 */
    public record DefaultGlossaryResponse(Long id, String name, Long seriesId, Long novelId,
                                          int entryCount) {}
}
