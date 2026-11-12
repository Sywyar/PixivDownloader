package top.sywyar.pixivdownload.core.work.filename;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("共享作品模板变量")
class WorkTemplateVariablesTest {
    @Test @DisplayName("导入别名统一身份，生成专用变量不能反向解析")
    void importAliasesAndScopes() {
        var artwork = WorkTemplateVariables.forImport("artwork_id");
        assertEquals("artworkId", artwork.id());
        assertSame(artwork, WorkTemplateVariables.forImport("ArtWorkId"));
        assertSame(artwork, WorkTemplateVariables.forImport("ART_WORK_ID"));
        assertTrue(artwork.numeric());
        assertFalse(WorkTemplateVariables.forImport("extension").download());
        for (String name : new String[]{"timestamp", "count", "ai", "R18", "collection_name", "collection_id", "seriesId", "ex", "unknown"})
            assertNull(WorkTemplateVariables.forImport(name));
        assertThrows(UnsupportedOperationException.class, () -> WorkTemplateVariables.all().clear());
    }

    @Test @DisplayName("系列 ID 仅供目录展开，收藏夹与系列允许目录缺省")
    void directoryContextsHaveExplicitScopes() {
        var series = WorkTemplateVariables.find("seriesId");
        assertSame(series, WorkTemplateVariables.find("series_id"));
        assertFalse(series.download());
        assertTrue(series.numeric());
        for (String name : new String[]{"seriesId", "collection_name", "collectionId"}) {
            var variable = WorkTemplateVariables.find(name);
            assertTrue(variable.directory());
            assertTrue(variable.directoryOptional());
        }
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
