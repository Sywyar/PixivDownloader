package top.sywyar.pixivdownload.core.work.filename;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("共享作品模板变量")
class WorkTemplateVariablesTest {
    @Test @DisplayName("导入使用标准变量拼写，生成专用变量不能反向解析")
    void importTokensAndScopes() {
        var artwork = WorkTemplateVariables.forImport("artwork_id");
        assertEquals("artworkId", artwork.id());
        assertTrue(artwork.numeric());
        assertFalse(WorkTemplateVariables.forImport("extension").download());
        for (String name : new String[]{"timestamp", "count", "ai", "R18", "collection_name", "collection_id",
                "series_id", "series_name", "series_title", "series_order", "username", "ex", "unknown"})
            assertNull(WorkTemplateVariables.forImport(name));
        assertThrows(UnsupportedOperationException.class, () -> WorkTemplateVariables.all().clear());
    }

    @Test @DisplayName("目录变量也支持下载命名，收藏夹与系列允许目录缺省")
    void directoryContextsHaveExplicitScopes() {
        var series = WorkTemplateVariables.find("series_id");
        assertTrue(series.download());
        assertTrue(series.numeric());
        for (String name : new String[]{"series_id", "series_name", "series_title", "series_order", "collection_name", "collection_id"}) {
            var variable = WorkTemplateVariables.find(name);
            assertTrue(variable.directory());
            assertTrue(variable.directoryOptional());
        }
        assertTrue(WorkTemplateVariables.all().stream().filter(WorkTemplateVariables.Variable::directory)
                .allMatch(WorkTemplateVariables.Variable::download));
    }

    @Test @DisplayName("不接受驼峰、大小写变体或增删下划线的变量别名")
    void rejectsNonCanonicalSpellings() {
        for (String name : new String[]{"artworkId", "artworkTitle", "authorId", "authorName",
                "collectionId", "collectionName", "seriesId", "seriesName", "seriesTitle", "seriesOrder", "SERIES_NAME", "ArtWorkId", "ARTWORK_ID",
                "ART_WORK_ID", "artworkid", "art_work_id", "artwork__id", "r18"}) {
            assertNull(WorkTemplateVariables.find(name), name);
            assertNull(WorkTemplateVariables.forImport(name), name);
        }
        assertNotNull(WorkTemplateVariables.find("R18"));
        assertNotNull(WorkTemplateVariables.find("R18+"));
    }

    @Test @DisplayName("展示的每个导入变量都能被同一目录解析")
    void displayedImportTokensResolve() {
        for (String token : WorkTemplateVariables.importTokens().split(", ")) {
            var variable = WorkTemplateVariables.forImport(token.substring(1, token.length() - 1));
            assertNotNull(variable);
            assertTrue(variable.importing());
        }
    }
}
