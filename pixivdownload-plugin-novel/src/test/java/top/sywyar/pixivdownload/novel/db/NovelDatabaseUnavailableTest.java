package top.sywyar.pixivdownload.novel.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("小说数据库缺席启动")
class NovelDatabaseUnavailableTest {
    @Test
    @DisplayName("无数据库时保留小说组件且不创建、清理、迁移或读取数据库")
    void skipsAllInitializationSqlWithoutDatabase() {
        NovelMapper mapper = mock(NovelMapper.class);
        WorkTagCatalog tags = mock(WorkTagCatalog.class);
        StoredPathCodec paths = mock(StoredPathCodec.class);
        var database = new NovelDatabase(mapper, tags, paths, () -> false);

        assertThatCode(database::init).doesNotThrowAnyException();

        verifyNoInteractions(mapper, tags, paths);
    }
}
