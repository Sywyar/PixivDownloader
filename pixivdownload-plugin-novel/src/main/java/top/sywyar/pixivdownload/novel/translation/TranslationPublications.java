package top.sywyar.pixivdownload.novel.translation;

import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.*;
import top.sywyar.pixivdownload.translation.api.TranslationDigest;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 收据与正式译文共用主库事务；每个变体只保留最近一次发布身份。 */
public final class TranslationPublications {
    private static final String TABLE = "novel_translation_publications";
    private final PluginMainDatabase database;
    private final NovelDatabase novels;
    public TranslationPublications(PluginMainDatabase database, NovelDatabase novels) {
        this.database = database; this.novels = novels;
    }
    public static SchemaContribution schema() {
        return new SchemaContribution(List.of(new TableSpec(TABLE, List.of(
                new ColumnSpec("variant", "TEXT", true, null, 1),
                new ColumnSpec("publication_id", "TEXT", true, null, 0),
                new ColumnSpec("digest", "TEXT", true, null, 0)),
                List.of(new IndexSpec("idx_novel_translation_publication_id", IndexOrigin.CREATE_INDEX, true, List.of("publication_id"))),
                null)), List.of(), List.of());
    }
    private static Expression eq(String column, Object value) { return call(EQUAL, column(TABLE, column), value(value)); }
    private static Query query(Expression where) {
        return new Query(TABLE, Map.of("publication_id", column(TABLE, "publication_id"), "digest", column(TABLE, "digest")),
                where, List.of(), List.of(), 1, 0);
    }
    public boolean published(UUID id) {
        Objects.requireNonNull(id);
        return !database.read(s -> s.query(query(eq("publication_id", id.toString())))).isEmpty();
    }
    public PublicationResult publish(Kind kind, long id, String variant, String body, String title, String description, Publication publication) {
        Objects.requireNonNull(publication);
        Objects.requireNonNull(publication.id());
        for (String digest : List.of(publication.sourceDigest(), publication.previousDigest(), publication.outputDigest()))
            if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("TRANSLATION_PUBLICATION_INVALID");
        if (!TranslationDigest.of(body, title, description).equals(publication.outputDigest()))
            throw new IllegalArgumentException("TRANSLATION_OUTPUT_CHANGED");
        String key = kind.name() + ":" + id + ":" + variant;
        String digest = TranslationDigest.of(key, publication.sourceDigest(), publication.previousDigest(), publication.outputDigest());
        return database.transaction(s -> {
            String table = kind == Kind.NOVEL ? "novels" : "novel_series";
            String keyColumn = kind == Kind.NOVEL ? "novel_id" : "series_id";
            Expression where = call(EQUAL, column(table, keyColumn), value(id));
            if (kind == Kind.NOVEL) where = call(AND, where, call(EQUAL, column(table, "deleted"), value(0)));
            if (s.update(table, Map.of(keyColumn, column(table, keyColumn)), where) != 1)
                return PublicationResult.SOURCE_CHANGED;
            var receipt = s.query(query(eq("variant", key)));
            if (!receipt.isEmpty() && publication.id().toString().equals(receipt.get(0).get("publication_id"))) {
                return digest.equals(receipt.get(0).get("digest")) ? PublicationResult.PUBLISHED : PublicationResult.CONFLICT;
            }
            String source;
            String previous;
            if (kind == Kind.NOVEL) {
                var novel = novels.getNovel(id);
                source = TranslationDigest.of(novel.rawContent(), novel.title(), novel.description());
                previous = TranslationDigest.of(novels.getTranslationContent(id, variant),
                        novels.getTranslationTitle(id, variant), novels.getTranslationDescription(id, variant));
            } else {
                var series = novels.getSeries(id);
                source = TranslationDigest.of(null, series.title(), series.description());
                previous = TranslationDigest.of(null, novels.getSeriesTitleTranslation(id, variant),
                        novels.getSeriesDescriptionTranslation(id, variant));
            }
            if (!source.equals(publication.sourceDigest())) return PublicationResult.SOURCE_CHANGED;
            if (!previous.equals(publication.previousDigest())) return PublicationResult.TARGET_CHANGED;
            if (kind == Kind.NOVEL) {
                if (body == null) novels.saveTranslationMetadata(id, variant, title, description);
                else novels.saveTranslation(id, variant, body, title, description);
            } else novels.saveSeriesTitleTranslation(id, variant, title, description);
            s.insert(TABLE, Map.of("variant", key, "publication_id", publication.id().toString(), "digest", digest),
                    new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("variant"),
                            Map.of("publication_id", new Incoming("publication_id"), "digest", new Incoming("digest"))));
            return PublicationResult.PUBLISHED;
        });
    }
    public List<Long> seriesNovelIds(long id, int offset, int limit) {
        if (id <= 0 || offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("TRANSLATION_PAGE_RANGE");
        String table = "novels";
        var query = new Query(table, Map.of("id", column(table, "novel_id")),
                call(AND, call(EQUAL, column(table, "series_id"), value(id)), call(EQUAL, column(table, "deleted"), value(0))),
                List.of(), List.of(new Query.Order(column(table, "series_order"), false, false),
                        new Query.Order(column(table, "novel_id"), false, false)), limit, offset);
        return database.read(s -> s.query(query)).stream().map(r -> (Long) r.get("id")).toList();
    }
}
