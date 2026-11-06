package top.sywyar.pixivdownload.novel.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("翻译术语表按有界查询分页")
class NovelGlossaryPagingTest {
    @Test
    @DisplayName("分页交给存储提供者，保留语言和原文的稳定排序")
    void boundsDatabaseQuery() throws Exception {
        var database = mock(PluginMainDatabase.class);
        var session = mock(DatabaseSession.class);
        when(database.read(any())).thenAnswer(call -> {
            DatabaseSession.Work<?> work = call.getArgument(0);
            return work.run(session);
        });
        when(session.query(any())).thenAnswer(call -> {
            Query query = call.getArgument(0);
            assertThat(query.limit()).isEqualTo(100);
            assertThat(query.offset()).isEqualTo(200);
            assertThat(query.orderBy()).hasSize(2);
            return List.of(Map.of("source", "名前", "lang_code", "en", "target", "Name"));
        });
        assertThat(new RelationalNovelMapper(database).findGlossaryEntries(7, 200, 100))
                .containsExactly(new NovelGlossaryEntry("名前", "en", "Name"));
    }
}
