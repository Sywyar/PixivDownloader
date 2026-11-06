package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.work.importing.LocalWorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkFileNameCatalog;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("小说纯本地文件登记")
class NovelFileImportHandlerTest {
    @org.junit.jupiter.api.io.TempDir Path root;

    @Test @DisplayName("本地正文随登记保存，公共资料保留未知值，已有小说不覆盖")
    void registersLocalFacts() throws Exception {
        var database = mock(NovelDatabase.class);
        var names = mock(WorkFileNameCatalog.class);
        when(names.getOrCreateTemplateId("{id}_p{page}")).thenReturn(3L);
        when(database.getUniqueTime()).thenReturn(100L);
        String content = "[chapter:本地章节]\n正文 🌸\n[newpage]\n第二页";
        java.nio.file.Files.writeString(root.resolve("Novel.TXT"), content, java.nio.charset.StandardCharsets.UTF_8);
        var request = new LocalWorkFileImportRequest(WorkType.NOVEL, 42, root, List.of(root.resolve("Novel.TXT")));
        var handler = new NovelFileImportHandler(database, names);
        assertTrue(handler.registerLocal(request));
        verify(database).insertNovel(42, "", root.toString(), 1, "txt", 100,
                null, null, null, null, 3, null, null, null, null, content.length(), null, 1, null, null, content, null);
        when(database.getNovel(42)).thenReturn(mock(NovelRecord.class));
        assertFalse(handler.registerLocal(request));
        verify(database, times(1)).getUniqueTime();
        verify(names, never()).getOrCreateAuthorNameId(anyString());
    }

    @Test @DisplayName("正文不可读取时拒绝登记，不留下空正文作品")
    void rejectsUnreadableContent() {
        var database = mock(NovelDatabase.class);
        var names = mock(WorkFileNameCatalog.class);
        var request = new LocalWorkFileImportRequest(WorkType.NOVEL, 42, root, List.of(root.resolve("missing.txt")));
        assertThrows(java.io.UncheckedIOException.class, () -> new NovelFileImportHandler(database, names).registerLocal(request));
        verify(database).getNovel(42);
        verifyNoMoreInteractions(database);
        verifyNoInteractions(names);
    }
}
