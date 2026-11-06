package top.sywyar.pixivdownload.novel.db;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("译文按语言和引擎隔离持久化")
class NovelTranslationStorageTest {
    @Test @DisplayName("同语言译文并存，覆盖与字段保留只影响所选引擎及其听书缓存")
    void separatesVariantsAndNarrationCaches() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE novels(novel_id INTEGER PRIMARY KEY,series_id INTEGER,deleted INTEGER NOT NULL DEFAULT 0)");
            jdbc.execute("INSERT INTO novels VALUES(7,9,0)");
            jdbc.execute("CREATE TABLE novel_series(series_id INTEGER PRIMARY KEY)");
            jdbc.execute("INSERT INTO novel_series VALUES(9)");
            jdbc.execute("CREATE TABLE novel_translations(novel_id INTEGER NOT NULL,lang_code TEXT NOT NULL,raw_content TEXT NOT NULL,title TEXT,description TEXT,created_time INTEGER NOT NULL,engine TEXT NOT NULL DEFAULT 'unknown',PRIMARY KEY(novel_id,lang_code,engine))");
            jdbc.execute("CREATE TABLE novel_series_title_translations(series_id INTEGER NOT NULL,lang_code TEXT NOT NULL,title TEXT NOT NULL,description TEXT,created_time INTEGER NOT NULL,engine TEXT NOT NULL DEFAULT 'unknown',PRIMARY KEY(series_id,lang_code,engine))");
            jdbc.execute("CREATE TABLE novel_narration_scripts(novel_id INTEGER NOT NULL,lang TEXT NOT NULL,PRIMARY KEY(novel_id,lang))");
            var mapper = NovelMapperTestFixture.mapper(source);
            var db = new NovelDatabase(mapper, mock(WorkTagCatalog.class), mock(StoredPathCodec.class), mock(DatabaseAvailability.class));
            for (String key : List.of("zh-CN", "zh-CN@ai", "zh-CN@youdao")) {
                db.saveTranslation(7, key, "body:" + key, "title:" + key, "description:" + key);
                db.saveSeriesTitleTranslation(9, key, "series:" + key, "series-description:" + key);
                jdbc.update("INSERT INTO novel_narration_scripts VALUES(7,?)", key);
            }
            db.saveTranslation(7, "zh-CN@youdao", "replacement", null, null);
            db.saveTranslationMetadata(7, "zh-CN@ai", "new-title", null);
            db.saveSeriesTitleTranslation(9, "zh-CN@youdao", "new-series", null);
            assertThat(db.getTranslationContent(7, "zh-CN")).isEqualTo("body:zh-CN");
            assertThat(db.getTranslationContent(7, "zh-CN@ai")).isEqualTo("body:zh-CN@ai");
            assertThat(db.getTranslationContent(7, "zh-CN@youdao")).isEqualTo("replacement");
            assertThat(db.getTranslationTitle(7, "zh-CN@youdao")).isEqualTo("title:zh-CN@youdao");
            assertThat(db.getTranslationTitle(7, "zh-CN@ai")).isEqualTo("new-title");
            assertThat(db.getTranslationDescription(7, "zh-CN@ai")).isEqualTo("description:zh-CN@ai");
            assertThat(db.hasTranslation(7, "zh-CN@libretranslate")).isFalse();
            assertThat(db.getTranslationContent(7, "zh-CN@libretranslate")).isNull();
            assertThat(db.getSeriesTitleTranslation(9, "zh-CN@ai")).isEqualTo("series:zh-CN@ai");
            assertThat(db.getSeriesDescriptionTranslation(9, "zh-CN@youdao")).isEqualTo("series-description:zh-CN@youdao");
            assertThat(db.getTranslationLangs(7)).containsExactly("zh-CN@ai", "zh-CN", "zh-CN@youdao");
            assertThat(db.getSeriesTranslatedLangs(9)).containsExactlyElementsOf(db.getTranslationLangs(7));
            assertThat(mapper.findTranslationLangsByIds(List.of(7L))).extracting(row -> row.value())
                    .containsExactlyElementsOf(db.getTranslationLangs(7));
            assertThat(jdbc.queryForList("SELECT lang FROM novel_narration_scripts ORDER BY lang", String.class))
                    .containsExactly("zh-CN", "zh-CN@ai");
            assertThat(jdbc.queryForList("SELECT DISTINCT lang_code FROM novel_translations", String.class)).containsExactly("zh-CN");
        } finally { source.destroy(); }
    }
}
