package top.sywyar.pixivdownload.novel.db;

import top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.novel.db.series.*;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationCharacter;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.*;
import java.util.function.Function;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 小说的业务持久化映射，结构与执行语法由所选提供者处理。 */
public class RelationalNovelMapper implements NovelMapper {
    private static final String N = "novels", S = "novel_series", V = "novel_narration_voices";
    private static final StorageFeatures.TextIndex CONTENT_INDEX =
            new StorageFeatures.TextIndex("novels_fts", N, "novel_id", "raw_content", "deleted", 0);
    private static final StorageFeatures.Cleanup DELETION = new StorageFeatures.Cleanup(
            "novel_soft_delete_cleanup", N, "novel_id", "deleted", 1,
            List.of("novel_tags", "novel_collections", "novel_images", "novel_translations", "novel_narration_scripts")
                    .stream().map(table -> new StorageFeatures.Dependent(table, "novel_id")).toList());
    private final PluginMainDatabase database;
    public RelationalNovelMapper(PluginMainDatabase database) { this.database = database; }

    private static Object stored(Object v) { return v instanceof Boolean b ? b ? 1L : 0L : v; }
    private static Expression literal(Object v) { return value(stored(v)); }
    private static Map<String, Object> row(Object... pairs) {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], stored(pairs[i + 1]));
        return values;
    }
    private static Map<String, Expression> fields(String table, String... names) {
        var fields = new LinkedHashMap<String, Expression>();
        for (String name : names) fields.put(name, column(table, name));
        return fields;
    }
    private static Expression eq(String table, String name, Object v) { return call(EQUAL, column(table, name), literal(v)); }
    private static Expression active() { return eq(N, "deleted", 0); }
    private static Expression member(String table, String name, Collection<Long> ids) {
        if (ids.isEmpty()) return call(EQUAL, value(1), value(0));
        var args = new ArrayList<Expression>(); args.add(column(table, name)); ids.forEach(id -> args.add(value(id)));
        return new Call(IN, args);
    }
    private static Query.Order asc(String table, String name) { return new Query.Order(column(table, name), false, true); }
    private static Query.Order desc(String table, String name) { return new Query.Order(column(table, name), true, false); }
    private static Query query(String table, Map<String, Expression> fields, Expression where, int limit, Query.Order... orders) {
        return new Query(table, fields, where, List.of(), List.of(orders), limit, 0);
    }
    private <T> List<T> select(Query query, Function<Map<String, Object>, T> map) {
        return database.read(s -> s.query(query)).stream().map(map).toList();
    }
    private static <T> T first(List<T> values) { return values.isEmpty() ? null : values.get(0); }
    private Object scalar(String table, String field, Expression where) {
        return first(select(query(table, fields(table, field), where, 1), r -> r.get(field)));
    }
    private long count(String table, Expression where) {
        return (Long) database.read(s -> s.query(Query.count(table, where))).get(0).get("count");
    }
    private int update(String table, Expression where, Object... pairs) {
        var changes = new LinkedHashMap<String, Expression>();
        for (int i = 0; i < pairs.length; i += 2) changes.put((String) pairs[i], literal(pairs[i + 1]));
        return database.transaction(s -> s.update(table, changes, where));
    }
    private void delete(String table, Expression where) { database.transaction(s -> s.delete(table, where)); }
    private int insert(String table, Map<String, Object> row, DatabaseSession.Conflict conflict) {
        return database.transaction(s -> s.insert(table, row, conflict));
    }
    private static DatabaseSession.Conflict replace(List<String> keys, Collection<String> columns) {
        var updates = new LinkedHashMap<String, Expression>();
        for (String column : columns) if (!keys.contains(column)) updates.put(column, new Incoming(column));
        return new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, keys, updates);
    }
    private int upsert(String table, List<String> keys, Map<String, Object> values) {
        return insert(table, values, replace(keys, values.keySet()));
    }
    private int millis(String table, String name) {
        var c = column(table, name);
        return database.transaction(s -> s.update(table, Map.of(name, call(MULTIPLY, c, value(1000))),
                call(AND, call(GREATER, c, value(0)), call(LESS, c, value(1_000_000_000_000L)))));
    }
    private static String text(Map<String, Object> r, String name) { return (String) r.get(name); }
    private static Long number(Map<String, Object> r, String name) { return (Long) r.get(name); }
    private static Integer integer(Map<String, Object> r, String name) { Long v = number(r, name); return v == null ? null : Math.toIntExact(v); }
    private static Boolean bool(Map<String, Object> r, String name) { Long v = number(r, name); return v == null ? null : v != 0; }

    private static Map<String, Expression> novelFields() {
        return fields(N, "novel_id", "title", "folder", "count", "extensions", "time", "r18", "is_ai", "author_id",
                "description", "file_name", "file_author_name_id", "series_id", "series_order", "word_count", "text_length",
                "reading_time_seconds", "page_count", "is_original", "x_language", "raw_content", "cover_ext", "deleted", "upload_time");
    }
    private static NovelRecord novel(Map<String, Object> r) {
        return new NovelRecord(number(r, "novel_id"), text(r, "title"), text(r, "folder"), integer(r, "count"),
                text(r, "extensions"), number(r, "time"), integer(r, "r18"), bool(r, "is_ai"), number(r, "author_id"),
                text(r, "description"), number(r, "file_name"), number(r, "file_author_name_id"), number(r, "series_id"),
                number(r, "series_order"), integer(r, "word_count"), integer(r, "text_length"), integer(r, "reading_time_seconds"),
                integer(r, "page_count"), bool(r, "is_original"), text(r, "x_language"), text(r, "raw_content"),
                text(r, "cover_ext"), Boolean.TRUE.equals(bool(r, "deleted")), number(r, "upload_time"));
    }
    private static Map<String, Expression> referenceFields() {
        return fields(V, "cast_id", "character_id", "ref_audio_ext", "ref_audio_text", "ref_audio_source", "ref_audio_time");
    }
    private static NovelNarrationVoiceRef reference(Map<String, Object> r) {
        return new NovelNarrationVoiceRef(number(r, "cast_id"), integer(r, "character_id"), text(r, "ref_audio_ext"),
                text(r, "ref_audio_text"), text(r, "ref_audio_source"), number(r, "ref_audio_time"));
    }
    private static Expression voice(long cast, int character) {
        return call(AND, eq(V, "cast_id", cast), eq(V, "character_id", character));
    }
    private static Expression translation(long novel, String lang) {
        return call(AND, eq("novel_translations", "novel_id", novel), eq("novel_translations", "lang_code", lang));
    }
    private static Query catalogue(String table, String details, String detailKey, Expression where, boolean bound) {
        var projection = fields(table, "id", "name", "series_id", "novel_id", "created_time", "updated_time");
        projection.put("members", new Scalar(Query.count(details,
                call(EQUAL, column(details, detailKey), column(table, "id")))));
        return query(table, projection, where, bound ? 1 : Integer.MAX_VALUE,
                bound ? asc(table, "id") : desc(table, "updated_time"), bound ? asc(table, "id") : desc(table, "id"));
    }
    private static NovelGlossary glossary(Map<String, Object> r) {
        return new NovelGlossary(number(r, "id"), text(r, "name"), number(r, "series_id"), number(r, "novel_id"),
                number(r, "created_time"), number(r, "updated_time"), integer(r, "members"));
    }
    private static NovelNarrationCast cast(Map<String, Object> r) {
        return new NovelNarrationCast(number(r, "id"), text(r, "name"), number(r, "series_id"), number(r, "novel_id"),
                number(r, "created_time"), number(r, "updated_time"), integer(r, "members"));
    }

    private static Query.Join novelJoin(String table) {
        return new Query.Join(Query.JoinKind.INNER, new Query.Table(N),
                call(EQUAL, column(N, "novel_id"), column(table, "novel_id")));
    }
    private List<NovelWorkDetailValueRow> detailValues(String table, String field, Collection<Long> ids) {
        return select(new Query(new Query.Table(table), List.of(novelJoin(table)), fields(table, "novel_id", field),
                call(AND, active(), member(table, "novel_id", ids)), List.of(), null,
                List.of(asc(table, "novel_id"), asc(table, field)), false, Integer.MAX_VALUE, 0),
                r -> new NovelWorkDetailValueRow(number(r, "novel_id"), text(r, field)));
    }
    private static Query seriesTags(Expression where) {
        String table = "novel_series_tags", tags = "tags";
        var fields = fields(tags, "tag_id", "name", "translated_name");
        fields.put("series_id", column(table, "series_id"));
        return new Query(new Query.Table(table), List.of(new Query.Join(Query.JoinKind.INNER, new Query.Table(tags),
                call(EQUAL, column(tags, "tag_id"), column(table, "tag_id")))), fields, where, List.of(), null,
                List.of(asc(table, "series_id"), asc(tags, "tag_id")), false, Integer.MAX_VALUE, 0);
    }

    @Override
    public void updateFileBaseName(long id, String name) {
        update(N, eq(N, "novel_id", id), "file_base_name", name);
    }

    @Override
    public void updateFileNameMaxLength(long id, int length) {
        update(N, eq(N, "novel_id", id), "file_name_max_length", length);
    }

    @Override
    public int migrateNovelTimestampsToMillis() {
        return millis("novels", "time");
    }

    @Override
    public int migrateNovelCollectionTimestampsToMillis() {
        return millis("novel_collections", "added_time");
    }

    @Override
    public void insertOrReplaceTranslation(long novelId, String langCode, String rawContent, String title, String description, long createdTime) {
        upsert("novel_translations", List.of("novel_id", "lang_code"),
                row("novel_id", novelId, "lang_code", langCode, "raw_content", rawContent, "title", title,
                        "description", description, "created_time", createdTime));
    }

    @Override
    public void updateTranslationTitle(long novelId, String langCode, String title) {
        update("novel_translations", translation(novelId, langCode), "title", title);
    }

    @Override
    public String findTranslationContent(long novelId, String langCode) {
        return (String) scalar("novel_translations", "raw_content", translation(novelId, langCode));
    }

    @Override
    public String findTranslationTitle(long novelId, String langCode) {
        return (String) scalar("novel_translations", "title", translation(novelId, langCode));
    }

    @Override
    public String findTranslationDescription(long novelId, String langCode) {
        return (String) scalar("novel_translations", "description", translation(novelId, langCode));
    }

    @Override
    public int countTranslation(long novelId, String langCode) {
        return Math.toIntExact(count("novel_translations", translation(novelId, langCode)));
    }

    @Override
    public List<String> findTranslationLangs(long novelId) {
        return select(query("novel_translations", fields("novel_translations", "lang_code"),
                eq("novel_translations", "novel_id", novelId), Integer.MAX_VALUE, asc("novel_translations", "lang_code")), r -> text(r, "lang_code"));
    }

    @Override
    public List<String> findSeriesTranslatedLangs(long seriesId) {
        String t = "novel_translations";
        var q = new Query(new Query.Table(t), List.of(novelJoin(t)), fields(t, "lang_code"),
                call(AND, active(), eq(N, "series_id", seriesId), call(GREATER, column(N, "series_id"), value(0))),
                List.of(), null, List.of(asc(t, "lang_code")), true, Integer.MAX_VALUE, 0);
        return select(q, r -> text(r, "lang_code"));
    }

    @Override
    public void deleteTranslations(long novelId) {
        delete("novel_translations", eq("novel_translations", "novel_id", novelId));
    }

    @Override
    public void insertOrReplaceSeriesTitleTranslation(long seriesId, String langCode, String title, String description, long createdTime) {
        upsert("novel_series_title_translations", List.of("series_id", "lang_code"),
                row("series_id", seriesId, "lang_code", langCode, "title", title, "description", description, "created_time", createdTime));
    }

    @Override
    public String findSeriesTitleTranslation(long seriesId, String langCode) {
        return (String) scalar("novel_series_title_translations", "title", call(AND, eq("novel_series_title_translations", "series_id", seriesId), eq("novel_series_title_translations", "lang_code", langCode)));
    }

    @Override
    public String findSeriesDescriptionTranslation(long seriesId, String langCode) {
        return (String) scalar("novel_series_title_translations", "description", call(AND, eq("novel_series_title_translations", "series_id", seriesId), eq("novel_series_title_translations", "lang_code", langCode)));
    }

    @Override
    public void insertGlossary(NovelGlossaryInsert glossary) {
        glossary.setId(database.transaction(s -> s.insertGenerated("novel_glossaries",
                row("name", glossary.getName(), "series_id", glossary.getSeriesId(), "novel_id", glossary.getNovelId(),
                        "created_time", glossary.getCreatedTime(), "updated_time", glossary.getUpdatedTime()), "id")));
    }

    @Override
    public NovelGlossary findGlossaryById(long id) {
        return first(select(catalogue("novel_glossaries", "novel_glossary_entries", "glossary_id", eq("novel_glossaries", "id", id), true), RelationalNovelMapper::glossary));
    }

    @Override
    public List<NovelGlossary> findAllGlossaries() {
        return select(catalogue("novel_glossaries", "novel_glossary_entries", "glossary_id", null, false), RelationalNovelMapper::glossary);
    }

    @Override
    public NovelGlossary findGlossaryBySeriesId(long seriesId) {
        return first(select(catalogue("novel_glossaries", "novel_glossary_entries", "glossary_id", eq("novel_glossaries", "series_id", seriesId), true), RelationalNovelMapper::glossary));
    }

    @Override
    public NovelGlossary findGlossaryByNovelId(long novelId) {
        return first(select(catalogue("novel_glossaries", "novel_glossary_entries", "glossary_id", eq("novel_glossaries", "novel_id", novelId), true), RelationalNovelMapper::glossary));
    }

    @Override
    public int updateGlossaryName(long id, String name, long updatedTime) {
        return update("novel_glossaries", eq("novel_glossaries", "id", id), "name", name, "updated_time", updatedTime);
    }

    @Override
    public int touchGlossary(long id, long updatedTime) {
        return update("novel_glossaries", eq("novel_glossaries", "id", id), "updated_time", updatedTime);
    }

    @Override
    public int countGlossaryById(long id) {
        return Math.toIntExact(count("novel_glossaries", eq("novel_glossaries", "id", id)));
    }

    @Override
    public void deleteGlossary(long id) {
        delete("novel_glossaries", eq("novel_glossaries", "id", id));
    }

    @Override
    public List<NovelGlossaryEntry> findGlossaryEntries(long glossaryId) {
        return select(query("novel_glossary_entries", fields("novel_glossary_entries", "source", "lang_code", "target"),
                eq("novel_glossary_entries", "glossary_id", glossaryId), Integer.MAX_VALUE,
                asc("novel_glossary_entries", "lang_code"), asc("novel_glossary_entries", "source")),
                r -> new NovelGlossaryEntry(text(r, "source"), text(r, "lang_code"), text(r, "target")));
    }

    @Override
    public void deleteGlossaryEntries(long glossaryId) {
        delete("novel_glossary_entries", eq("novel_glossary_entries", "glossary_id", glossaryId));
    }

    @Override
    public void upsertGlossaryEntry(long glossaryId, String source, String langCode, String target, long createdTime) {
        upsert("novel_glossary_entries", List.of("glossary_id", "source", "lang_code"), row("glossary_id", glossaryId, "source", source, "lang_code", langCode, "target", target, "created_time", createdTime));
    }

    @Override
    public void insertGlossaryEntryIfAbsent(long glossaryId, String source, String langCode, String target, long createdTime) {
        insert("novel_glossary_entries", row("glossary_id", glossaryId, "source", source, "lang_code", langCode, "target", target, "created_time", createdTime), DatabaseSession.Conflict.ignore());
    }

    @Override
    public void insertNarrationCast(NovelNarrationCastInsert cast) {
        cast.setId(database.transaction(s -> s.insertGenerated("novel_narration_casts",
                row("name", cast.getName(), "series_id", cast.getSeriesId(), "novel_id", cast.getNovelId(),
                        "created_time", cast.getCreatedTime(), "updated_time", cast.getUpdatedTime()), "id")));
    }

    @Override
    public NovelNarrationCast findNarrationCastById(long id) {
        return first(select(catalogue("novel_narration_casts", "novel_narration_voices", "cast_id", eq("novel_narration_casts", "id", id), true), RelationalNovelMapper::cast));
    }

    @Override
    public List<NovelNarrationCast> findAllNarrationCasts() {
        return select(catalogue("novel_narration_casts", "novel_narration_voices", "cast_id", null, false), RelationalNovelMapper::cast);
    }

    @Override
    public NovelNarrationCast findNarrationCastBySeriesId(long seriesId) {
        return first(select(catalogue("novel_narration_casts", "novel_narration_voices", "cast_id", eq("novel_narration_casts", "series_id", seriesId), true), RelationalNovelMapper::cast));
    }

    @Override
    public NovelNarrationCast findNarrationCastByNovelId(long novelId) {
        return first(select(catalogue("novel_narration_casts", "novel_narration_voices", "cast_id", eq("novel_narration_casts", "novel_id", novelId), true), RelationalNovelMapper::cast));
    }

    @Override
    public int touchNarrationCast(long id, long updatedTime) {
        return update("novel_narration_casts", eq("novel_narration_casts", "id", id), "updated_time", updatedTime);
    }

    @Override
    public int countNarrationCastById(long id) {
        return Math.toIntExact(count("novel_narration_casts", eq("novel_narration_casts", "id", id)));
    }

    @Override
    public List<top.sywyar.pixivdownload.novel.narration.analysis.NarrationCharacter> findNarrationVoices(long castId) {
        return select(query(V, fields(V, "character_id", "name", "gender", "age", "control_instruction", "edited_by_user"),
                eq(V, "cast_id", castId), Integer.MAX_VALUE, asc(V, "character_id")),
                r -> new NarrationCharacter(integer(r, "character_id"), text(r, "name"), text(r, "gender"), text(r, "age"),
                        text(r, "control_instruction"), number(r, "character_id") == 0, Boolean.TRUE.equals(bool(r, "edited_by_user"))));
    }

    @Override
    public Integer maxNarrationVoiceId(long castId) {
        return first(select(query(V, Map.of("maximum", call(MAX, column(V, "character_id"))), eq(V, "cast_id", castId), 1), r -> integer(r, "maximum")));
    }

    @Override
    public void insertNarrationVoiceIfAbsent(
            long castId,
            int characterId,
            String name,
            String gender,
            String age,
            String controlInstruction,
            boolean editedByUser,
            long createdTime
    ) {
        insert(V, row("cast_id", castId, "character_id", characterId, "name", name, "gender", gender, "age", age,
                "control_instruction", controlInstruction, "edited_by_user", editedByUser, "created_time", createdTime), DatabaseSession.Conflict.ignore());
    }

    @Override
    public void upsertNarrationVoice(
            long castId,
            int characterId,
            String name,
            String gender,
            String age,
            String controlInstruction,
            boolean editedByUser,
            long createdTime
    ) {
        insert(V, row("cast_id", castId, "character_id", characterId, "name", name, "gender", gender, "age", age,
                "control_instruction", controlInstruction, "edited_by_user", editedByUser, "created_time", createdTime),
                replace(List.of("cast_id", "character_id"), List.of("name", "gender", "age", "control_instruction", "edited_by_user")));
    }

    @Override
    public int updateNarrationVoiceInstruction(long castId, int characterId, String controlInstruction, boolean editedByUser) {
        return update(V, voice(castId, characterId), "control_instruction", controlInstruction, "edited_by_user", editedByUser);
    }

    @Override
    public int updateNarrationVoiceName(long castId, int characterId, String name) {
        return update(V, call(AND, voice(castId, characterId), eq(V, "edited_by_user", 0)), "name", name);
    }

    @Override
    public int updateNarrationVoiceReference(long castId, int characterId, String ext, String text, String source, Long time) {
        return update(V, voice(castId, characterId), "ref_audio_ext", ext, "ref_audio_text", text, "ref_audio_source", source, "ref_audio_time", time);
    }

    @Override
    public int clearNarrationVoiceReference(long castId, int characterId) {
        return update(V, voice(castId, characterId), "ref_audio_ext", null, "ref_audio_text", null, "ref_audio_source", null, "ref_audio_time", null);
    }

    @Override
    public NovelNarrationVoiceRef findNarrationVoiceRef(long castId, int characterId) {
        return first(select(query(V, referenceFields(), voice(castId, characterId), 1), RelationalNovelMapper::reference));
    }

    @Override
    public List<NovelNarrationVoiceRef> findNarrationVoiceRefs(long castId) {
        return select(query(V, referenceFields(), call(AND, eq(V, "cast_id", castId),
                call(NOT, call(IS_NULL, column(V, "ref_audio_ext")))), Integer.MAX_VALUE), RelationalNovelMapper::reference);
    }

    @Override
    public void upsertNarrationScript(long novelId, String lang, long castId, int segmentSize, long analyzedTime, String scriptJson) {
        upsert("novel_narration_scripts", List.of("novel_id", "lang"), row("novel_id", novelId, "lang", lang,
                "cast_id", castId, "segment_size", segmentSize, "analyzed_time", analyzedTime, "script_json", scriptJson));
    }

    @Override
    public NovelNarrationScriptRow findNarrationScript(long novelId, String lang) {
        return first(select(query("novel_narration_scripts", fields("novel_narration_scripts",
                "novel_id", "lang", "cast_id", "segment_size", "analyzed_time", "script_json"),
                call(AND, eq("novel_narration_scripts", "novel_id", novelId), eq("novel_narration_scripts", "lang", lang)), 1),
                r -> new NovelNarrationScriptRow(number(r, "novel_id"), text(r, "lang"), number(r, "cast_id"),
                        integer(r, "segment_size"), number(r, "analyzed_time"), text(r, "script_json"))));
    }

    @Override
    public void deleteNarrationScripts(long novelId) {
        delete("novel_narration_scripts", eq("novel_narration_scripts", "novel_id", novelId));
    }

    @Override
    public void deleteNarrationScript(long novelId, String lang) {
        delete("novel_narration_scripts", call(AND, eq("novel_narration_scripts", "novel_id", novelId),
                eq("novel_narration_scripts", "lang", lang)));
    }

    @Override
    public void createNovelSoftDeleteCleanupTrigger() {
        database.transaction(s -> { s.initializeCleanup(DELETION); return null; });
    }

    @Override
    public void cleanupExistingDeletedNovelState() {
        update(N, eq(N, "deleted", 1), "deleted", 1);
    }

    @Override
    public int migrateNovelSeriesTimestampsToMillis() {
        return millis("novel_series", "updated_time");
    }

    @Override
    public void insertNovelImage(long novelId, String imageId, String ext) {
        upsert("novel_images", List.of("novel_id", "image_id"), row("novel_id", novelId, "image_id", imageId, "ext", ext));
    }

    @Override
    public String findNovelImageExt(long novelId, String imageId) {
        return (String) scalar("novel_images", "ext", call(AND, eq("novel_images", "novel_id", novelId), eq("novel_images", "image_id", imageId)));
    }

    @Override
    public List<String> findNovelImageIds(long novelId) {
        return select(query("novel_images", fields("novel_images", "image_id"), eq("novel_images", "novel_id", novelId), Integer.MAX_VALUE), r -> text(r, "image_id"));
    }

    @Override
    public List<NovelWorkDetailValueRow> findNovelImageIdsByIds(Collection<Long> ids) {
        return detailValues("novel_images", "image_id", ids);
    }

    @Override
    public void deleteNovelImages(long novelId) {
        delete("novel_images", eq("novel_images", "novel_id", novelId));
    }

    @Override
    public NovelRecord findById(long novelId) {
        return first(select(query(N, novelFields(), eq(N, "novel_id", novelId), 1), RelationalNovelMapper::novel));
    }

    @Override
    public List<NovelWorkDetailsRow> findWorkDetailsByIds(Collection<Long> ids) {
        return select(query(N, fields(N, "novel_id", "word_count", "text_length", "reading_time_seconds",
                "page_count", "x_language", "cover_ext"), call(AND, active(), member(N, "novel_id", ids)), Integer.MAX_VALUE),
                r -> new NovelWorkDetailsRow(number(r, "novel_id"), integer(r, "word_count"), integer(r, "text_length"),
                        integer(r, "reading_time_seconds"), integer(r, "page_count"), text(r, "x_language"), text(r, "cover_ext")));
    }

    @Override
    public List<NovelWordCountRow> findWordCountsByIds(Collection<Long> ids) {
        return select(query(N, fields(N, "novel_id", "word_count"),
                call(AND, active(), member(N, "novel_id", ids)), Integer.MAX_VALUE),
                r -> new NovelWordCountRow(number(r, "novel_id"), integer(r, "word_count")));
    }

    @Override
    public List<NovelWorkDetailValueRow> findTranslationLangsByIds(Collection<Long> ids) {
        return detailValues("novel_translations", "lang_code", ids);
    }

    @Override
    public List<NovelRecord> findBySeriesId(long seriesId) {
        return select(query(N, novelFields(), call(AND, active(), eq(N, "series_id", seriesId),
                call(GREATER, column(N, "series_id"), value(0))), Integer.MAX_VALUE, asc(N, "series_order"), asc(N, "time")), RelationalNovelMapper::novel);
    }

    @Override
    public int countActiveById(long novelId) {
        return Math.toIntExact(count(N, call(AND, active(), eq(N, "novel_id", novelId))));
    }

    @Override
    public int countByTime(long time) {
        return Math.toIntExact(count(N, eq(N, "time", time)));
    }

    @Override
    public Long findMaxTime() {
        return first(select(query(N, Map.of("maximum", call(MAX, column(N, "time"))), null, 1), r -> number(r, "maximum")));
    }

    @Override
    public long countAll() {
        return count(N, active());
    }

    @Override
    public void insertOrReplace(
            long novelId,
            String title,
            String folder,
            int count,
            String extensions,
            long time,
            Integer xRestrict,
            Boolean isAi,
            Long authorId,
            String description,
            long fileName,
            Long fileAuthorNameId,
            Long seriesId,
            Long seriesOrder,
            Integer wordCount,
            Integer textLength,
            Integer readingTimeSeconds,
            Integer pageCount,
            Boolean isOriginal,
            String xLanguage,
            String rawContent,
            String coverExt
    ) {
        var values = row("novel_id", novelId, "title", title, "folder", folder, "count", count,
                "extensions", extensions, "time", time, "r18", xRestrict, "is_ai", isAi, "author_id", authorId,
                "description", description, "file_name", fileName, "file_author_name_id", fileAuthorNameId,
                "series_id", seriesId, "series_order", seriesOrder, "word_count", wordCount, "text_length", textLength,
                "reading_time_seconds", readingTimeSeconds, "page_count", pageCount, "is_original", isOriginal,
                "x_language", xLanguage, "raw_content", rawContent, "cover_ext", coverExt,
                "deleted", 0, "upload_time", null, "file_base_name", null, "metadata_json", null,
                "file_name_max_length", top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter.MAX_BASENAME_LENGTH);
        database.transaction(s -> {
            s.delete(N, call(AND, eq(N, "time", time), call(NOT_EQUAL, column(N, "novel_id"), value(novelId))));
            return s.insert(N, values, replace(List.of("novel_id"), values.keySet()));
        });
    }

    @Override
    public void updateCoverExt(long novelId, String coverExt) {
        update(N, eq(N, "novel_id", novelId), "cover_ext", coverExt);
    }

    @Override
    public void updateUploadTime(long novelId, Long uploadTime) {
        update(N, eq(N, "novel_id", novelId), "upload_time", uploadTime);
    }

    @Override
    public void updateExtensions(long novelId, String extensions) {
        update(N, eq(N, "novel_id", novelId), "extensions", extensions);
    }

    @Override
    public void updateSeriesInfo(long novelId, Long seriesId, Long seriesOrder) {
        update(N, eq(N, "novel_id", novelId), "series_id", seriesId, "series_order", seriesOrder);
    }

    @Override
    public List<Long> findIdsMissingSeries() {
        return select(query(N, fields(N, "novel_id"), call(AND, active(), call(IS_NULL, column(N, "series_id"))), Integer.MAX_VALUE), r -> number(r, "novel_id"));
    }

    @Override
    public List<Long> findIdsMissingAuthor() {
        return select(query(N, fields(N, "novel_id"), call(AND, active(), call(IS_NULL, column(N, "author_id"))), Integer.MAX_VALUE), r -> number(r, "novel_id"));
    }

    @Override
    public void updateAuthorId(long novelId, long authorId) {
        update(N, eq(N, "novel_id", novelId), "author_id", authorId);
    }

    @Override
    public int insertSeriesIfAbsent(long id, String title, Long authorId, long updatedTime) {
        return insert(S, row("series_id", id, "title", title, "author_id", authorId, "updated_time", updatedTime), DatabaseSession.Conflict.ignore());
    }

    @Override
    public int updateSeries(long id, String title, Long authorId, long updatedTime) {
        return update(S, eq(S, "series_id", id), "title", title, "author_id", authorId, "updated_time", updatedTime);
    }

    @Override
    public int updateNovelSeriesMetadata(long id, String description, String coverExt, String coverFolder) {
        return update(S, eq(S, "series_id", id), "description", description, "cover_ext", coverExt, "cover_folder", coverFolder);
    }

    @Override
    public NovelSeries findSeriesById(long id) {
        return first(select(query(S, fields(S, "series_id", "title", "author_id", "updated_time", "description", "cover_ext", "cover_folder"), eq(S, "series_id", id), 1),
                r -> new NovelSeries(number(r, "series_id"), text(r, "title"), number(r, "author_id"), number(r, "updated_time"), text(r, "description"), text(r, "cover_ext"), text(r, "cover_folder"))));
    }

    @Override
    public List<NovelSeriesCatalogRow> findNovelSeriesCatalog() {
        var fields = fields(N, "series_id");
        fields.put("title", new Conditional(call(IS_NULL, column(S, "series_id")),
                call(TEXT, column(N, "series_id")), column(S, "title")));
        fields.put("author_id", column(S, "author_id"));
        fields.put("novel_count", call(COUNT));
        fields.put("cover_ext", column(S, "cover_ext"));
        var q = new Query(new Query.Table(N), List.of(new Query.Join(Query.JoinKind.LEFT, new Query.Table(S),
                call(EQUAL, column(N, "series_id"), column(S, "series_id")))), fields,
                call(AND, active(), call(GREATER, column(N, "series_id"), value(0))),
                List.of(column(N, "series_id"), column(S, "series_id"), column(S, "title"), column(S, "author_id"), column(S, "cover_ext")),
                null, List.of(asc(N, "series_id")), false, Integer.MAX_VALUE, 0);
        return select(q, r -> new NovelSeriesCatalogRow(number(r, "series_id"), text(r, "title"),
                number(r, "author_id"), number(r, "novel_count"), text(r, "cover_ext")));
    }

    @Override
    public void insertNovelTag(long novelId, long tagId) {
        insert("novel_tags", row("novel_id", novelId, "tag_id", tagId), DatabaseSession.Conflict.ignore());
    }

    @Override
    public void deleteNovelTags(long novelId) {
        delete("novel_tags", eq("novel_tags", "novel_id", novelId));
    }

    @Override
    public Integer existsTagsForNovel(long novelId) {
        return first(select(query("novel_tags", Map.of("found", value(1)), eq("novel_tags", "novel_id", novelId), 1), r -> integer(r, "found")));
    }

    @Override
    public void insertNovelSeriesTag(long seriesId, long tagId) {
        insert("novel_series_tags", row("series_id", seriesId, "tag_id", tagId), DatabaseSession.Conflict.ignore());
    }

    @Override
    public void deleteNovelSeriesTags(long seriesId) {
        delete("novel_series_tags", eq("novel_series_tags", "series_id", seriesId));
    }

    @Override
    public List<NovelTagRow> findNovelSeriesTags(long seriesId) {
        return select(seriesTags(eq("novel_series_tags", "series_id", seriesId)),
                r -> new NovelTagRow(number(r, "tag_id"), text(r, "name"), text(r, "translated_name")));
    }

    @Override
    public List<NovelSeriesTagRow> findNovelSeriesTagsBySeriesIds(Collection<Long> seriesIds) {
        return select(seriesTags(member("novel_series_tags", "series_id", seriesIds)),
                r -> new NovelSeriesTagRow(number(r, "series_id"), number(r, "tag_id"), text(r, "name"), text(r, "translated_name")));
    }

    @Override
    public void deleteAllNovelCollections(long novelId) {
        delete("novel_collections", eq("novel_collections", "novel_id", novelId));
    }

    @Override
    public List<NovelDownloadedStatusRow> findDownloadedStatuses(Collection<Long> ids) {
        return select(query(N, fields(N, "novel_id", "deleted"), member(N, "novel_id", ids), Integer.MAX_VALUE),
                r -> new NovelDownloadedStatusRow(number(r, "novel_id"), Boolean.TRUE.equals(bool(r, "deleted"))));
    }

    @Override
    public long countNovelsByCollectionId(long collectionId) {
        return count("novel_collections", eq("novel_collections", "collection_id", collectionId));
    }

    @Override
    @Transactional(propagation = Propagation.NESTED)
    public void initializeContentSearch() {
        database.transaction(s -> s.initializeTextIndex(CONTENT_INDEX));
    }

    @Override
    @Transactional(propagation = Propagation.NESTED)
    public void refreshContentSearch(Long novelId) {
        database.transaction(s -> s.refreshTextIndex(CONTENT_INDEX, novelId));
    }

    @Override
    @Transactional(propagation = Propagation.NESTED)
    public List<Long> searchContent(String term) {
        return database.read(s -> s.searchText(CONTENT_INDEX, term));
    }
}
