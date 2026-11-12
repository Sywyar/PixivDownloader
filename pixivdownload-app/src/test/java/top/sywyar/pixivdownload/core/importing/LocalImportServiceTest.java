package top.sywyar.pixivdownload.core.importing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("本地模板导入异步扫描、索引和取消")
class LocalImportServiceTest {
    @TempDir Path root;
    private LocalImportService service;
    private final LocalImportConfig config = new LocalImportConfig();
    private final WorkFileImporter importer = mock(WorkFileImporter.class);
    private final PixivDatabase database = mock(PixivDatabase.class);
    private final ExternalWorkFiles external = mock(ExternalWorkFiles.class);

    @BeforeEach void setup() { service = new LocalImportService(config, importer, database, external); }
    @AfterEach void close() { service.close(); }
    private void file(String relative) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{(byte)137,80,78,71,13,10,26,10});
    }
    private Snapshot await(UUID id, Phase phase) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Snapshot state;
        do {
            state = service.status(id);
            if (state.phase() == phase) return state;
            if (state.phase() == Phase.FAILED) fail("Scan failed: " + state.problems());
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        throw new AssertionError(state);
    }

    @Test @DisplayName("按作者目录扫描后复用索引，显式刷新发现新增文件")
    void indexesBatchAndRefreshes() throws Exception {
        file("10/42/a.png"); file("10/43/b.png");
        String template = root + "/{author_id}/{artwork_id}/";
        var a = service.scan(new Request(List.of(template), List.of(42L, 44L), List.of(), false, ""));
        assertThat(await(a.id(), Phase.READY).artworks()).isEqualTo(2);
        assertThat(service.items(a.id(), 0, 10)).extracting(Item::disposition).containsExactly(Disposition.NEW, Disposition.MISSING);
        file("10/44/c.png");
        var b = service.scan(new Request(List.of(template), List.of(44L), List.of(), false, ""));
        assertThat(await(b.id(), Phase.READY).cachedIndex()).isTrue();
        assertThat(service.items(b.id(), 0, 10).getFirst().disposition()).isEqualTo(Disposition.MISSING);
        var c = service.scan(new Request(List.of(template), List.of(44L), List.of(), true, ""));
        assertThat(await(c.id(), Phase.READY).cachedIndex()).isFalse();
        assertThat(service.items(c.id(), 0, 10).getFirst().disposition()).isEqualTo(Disposition.NEW);
    }

    @Test @DisplayName("预设支持 ID 列表，执行与重复提交共用同一任务")
    void presetsAndIdempotentStart() throws Exception {
        file("42/a.png");
        config.setPresets(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                List.of(new Preset("Pictures", root + "/{artwork_id}/", true))));
        when(importer.importLocalFiles(any())).thenReturn(true);
        var a = service.scan(new Request(List.of(), List.of(42L), List.of("Pictures"), false, ""));
        await(a.id(), Phase.READY); service.start(a.id());
        assertThat(await(a.id(), Phase.COMPLETED).imported()).isEqualTo(1);
        assertThat(service.start(a.id()).phase()).isEqualTo(Phase.COMPLETED);
        verify(importer, times(1)).importLocalFiles(argThat(r -> r.workId() == 42 && r.parsed() && r.sources().getFirst().page() == null));
    }

    @Test @DisplayName("按模板逐层排除不匹配目录，不进入无关的深层树")
    void prunesUnrelatedDirectories() throws Exception {
        file("10/42/a.png");
        Path unrelated = root.resolve("unrelated");
        for (int i = 0; i < 66; i++) unrelated = unrelated.resolve("d");
        Files.createDirectories(unrelated);
        String template = root + "/{author_id}/{artwork_id}/";
        var parsed = new LocalImportTemplate(template);
        assertThat(parsed.acceptsDirectory(root.resolve("10/42/subfolder"))).isTrue();
        assertThat(parsed.acceptsDirectory(root.resolve("unrelated"))).isFalse();
        assertThat(new LocalImportTemplate(root + "/{artwork_id}.{extension}").acceptsDirectory(root.resolve("10"))).isFalse();
        var preview = service.scan(new Request(List.of(template), List.of(), List.of(), false, ""));
        assertThat(await(preview.id(), Phase.READY).artworks()).isEqualTo(1);
    }

    @Test @DisplayName("取消等当前登记退出，已完成作品保留且后续作品不执行")
    void cancelWaitsForRealExit() throws Exception {
        file("42/a.png"); file("43/b.png");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(importer.importLocalFiles(any())).thenAnswer(call -> { entered.countDown(); release.await(); return true; });
        var a = service.scan(new Request(List.of(root + "/{artwork_id}/"), List.of(), List.of(), false, ""));
        await(a.id(), Phase.READY); service.start(a.id());
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(a.id()).phase()).isEqualTo(Phase.CANCELLING);
            assertThatThrownBy(() -> service.scan(new Request(List.of(root + "/{artwork_id}/"), List.of(), List.of(), false, "")))
                    .hasMessage("IMPORT_BUSY");
        } finally { release.countDown(); }
        assertThat(await(a.id(), Phase.CANCELLED).imported()).isEqualTo(1);
        verify(importer, times(1)).importLocalFiles(any());
    }

    @Test @DisplayName("同页多文件在预览报告冲突，不进入登记")
    void conflictsAreReviewable() throws Exception {
        file("42_p0_a.png"); file("42_p0_b.png");
        var a = service.scan(new Request(List.of(root + "/{artwork_id}_p{page}_{name}.{extension}"), List.of(), List.of(), false, ""));
        await(a.id(), Phase.READY);
        assertThat(service.items(a.id(), 0, 10).getFirst().disposition()).isEqualTo(Disposition.CONFLICT);
        service.start(a.id());
        assertThat(await(a.id(), Phase.COMPLETED).failed()).isEqualTo(1);
        verifyNoInteractions(importer);
    }
}
