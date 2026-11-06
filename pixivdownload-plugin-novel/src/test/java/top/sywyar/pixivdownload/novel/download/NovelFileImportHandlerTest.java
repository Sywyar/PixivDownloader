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
    @Test @DisplayName("没有标题、作者和正文时登记实际格式并保留未知值，已有小说不覆盖")
    void registersLocalFacts() {
        var database = mock(NovelDatabase.class);
        var names = mock(WorkFileNameCatalog.class);
        when(names.getOrCreateTemplateId("{id}_p{page}")).thenReturn(3L);
        when(database.getUniqueTime()).thenReturn(100L);
        Path root = Path.of("source").toAbsolutePath();
        var request = new LocalWorkFileImportRequest(WorkType.NOVEL, 42, root, List.of(root.resolve("Novel.TXT")));
        var handler = new NovelFileImportHandler(database, names);
        assertTrue(handler.registerLocal(request));
        verify(database).insertNovel(42, "", root.toString(), 1, "txt", 100,
                null, null, null, null, 3, null, null, null, null, null, null, 1, null, null, null, null);
        when(database.getNovel(42)).thenReturn(mock(NovelRecord.class));
        assertFalse(handler.registerLocal(request));
        verify(database, times(1)).getUniqueTime();
        verify(names, never()).getOrCreateAuthorNameId(anyString());
    }
}
