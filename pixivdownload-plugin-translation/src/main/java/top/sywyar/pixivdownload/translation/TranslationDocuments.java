package top.sywyar.pixivdownload.translation;

import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.*;

import top.sywyar.pixivdownload.translation.api.TranslationVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 长文按块跨越同步服务边界；每次传输始终使用同一 publication。 */
public class TranslationDocuments {
    public static final ServiceKey<NovelTranslationDocuments> KEY =
            new ServiceKey<>(NovelTranslationDocuments.CONTRACT_ID, NovelTranslationDocuments.class);
    private final PluginServices services;
    private final NovelTranslationDocuments bound;
    private final String engine;

    public TranslationDocuments(PluginServices services) { this.services = services; this.bound = null; this.engine = "ai"; }
    public TranslationDocuments(NovelTranslationDocuments bound) { this(bound, "ai"); }
    private TranslationDocuments(NovelTranslationDocuments bound, String engine) {
        this.services = null; this.bound = bound; this.engine = engine;
    }
    private String key(String language) { return new TranslationVariant(language, engine).key(); }
    private NovelTranslationDocuments port() { return bound == null ? services.require(KEY) : bound; }
    public TranslationDocuments snapshot(String engine) { return new TranslationDocuments(port(), engine); }

    public record Content(long novelId, Long seriesId, String rawContent, String title, String description) {}

    public Content getNovel(long id) { return source(Kind.NOVEL, id); }
    public Content getSeries(long id) { return source(Kind.SERIES, id); }
    public Content source(Kind kind, long id) {
        var port = port();
        return read(port, port.openSource(kind, id));
    }
    public Content translation(Kind kind, long id, String language) {
        var port = port();
        return read(port, port.openTranslation(kind, id, key(language)));
    }
    private Content read(NovelTranslationDocuments port, Document document) {
        if (document == null) return null;
        try {
            return new Content(document.id(), document.seriesId(),
                    read(port, document.transfer(), Field.BODY, document.bodyLength()),
                    read(port, document.transfer(), Field.TITLE, document.titleLength()),
                    read(port, document.transfer(), Field.DESCRIPTION, document.descriptionLength()));
        } finally { port.close(document.transfer()); }
    }
    private String read(NovelTranslationDocuments port, UUID token, Field field, int length) {
        if (length < 0) return null;
        StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            int size = Math.min(NovelTranslationDocuments.CHUNK_CHARS, length - text.length());
            String chunk = port.read(token, field, text.length(), size);
            if (chunk == null || chunk.length() != size) throw new IllegalStateException("TRANSLATION_READ_INCOMPLETE");
            text.append(chunk);
        }
        return text.toString();
    }
    public boolean translated(long id, String lang, boolean body, boolean title, boolean description) {
        return port().translated(Kind.NOVEL, id, key(lang), body, title, description);
    }
    public boolean translated(Kind kind, long id, String lang, boolean body, boolean title, boolean description) {
        return port().translated(kind, id, key(lang), body, title, description);
    }
    public String getSeriesTitleTranslation(long id, String lang) { return translation(Kind.SERIES, id, lang).title(); }
    public String getSeriesDescriptionTranslation(long id, String lang) { return translation(Kind.SERIES, id, lang).description(); }
    public void saveTranslation(long id, String lang, String body, String title, String description) {
        save(Kind.NOVEL, id, lang, body, title, description);
    }
    public void saveTranslationMetadata(long id, String lang, String title, String description) {
        save(Kind.NOVEL, id, lang, null, title, description);
    }
    public void saveSeriesTitleTranslation(long id, String lang, String title, String description) {
        save(Kind.SERIES, id, lang, null, title, description);
    }
    public void save(Kind kind, long id, String lang, String body, String title, String description) {
        publish(kind, id, lang, body, title, description, null);
    }
    public boolean published(UUID publication) { return port().published(publication); }
    public List<Long> seriesNovelIds(long id, int offset, int limit) { return port().seriesNovelIds(id, offset, limit); }
    public void publish(Kind kind, long id, String lang, String body, String title, String description, Publication publication) {
        var port = port();
        UUID token = port.beginWrite(kind, id, key(lang), size(body), size(title), size(description));
        try {
            append(port, token, Field.BODY, body);
            append(port, token, Field.TITLE, title);
            append(port, token, Field.DESCRIPTION, description);
            if (publication == null) port.commit(token);
            else switch (port.publish(token, publication)) {
                case PUBLISHED -> { }
                case SOURCE_CHANGED -> throw new IllegalStateException("translation.source-changed");
                case TARGET_CHANGED, CONFLICT -> throw new IllegalStateException("translation.target-changed");
            }
        } finally { port.close(token); }
    }
    private static int size(String text) { return text == null ? -1 : text.length(); }
    private void append(NovelTranslationDocuments port, UUID token, Field field, String text) {
        if (text == null) return;
        for (int offset = 0; offset < text.length(); offset += NovelTranslationDocuments.CHUNK_CHARS)
            port.append(token, field, offset, text.substring(offset,
                    Math.min(text.length(), offset + NovelTranslationDocuments.CHUNK_CHARS)));
    }
    public List<Term> glossaryEntries(long id, int maximum) {
        var port = port();
        List<Term> entries = new ArrayList<>();
        while (entries.size() < maximum) {
            var page = port.glossaryEntries(id, entries.size(), Math.min(100, maximum - entries.size()));
            if (page.isEmpty()) break;
            entries.addAll(page);
        }
        return entries;
    }
    public void mergeTerms(long id, String lang, List<Term> terms) {
        var port = port();
        for (int offset = 0; offset < terms.size(); offset += 100)
            port.mergeTerms(id, lang, terms.subList(offset, Math.min(terms.size(), offset + 100)));
    }
    public List<TitleReference> titleReferences(long id, String lang, int maximum) {
        return port().titleReferences(id, key(lang), 0, maximum);
    }
    public Long defaultGlossary(long id) { return port().defaultGlossary(id); }
    public void mergeSeries(long id, String format) { port().mergeSeries(id, format, null); }
    public void mergeSeries(long id, String format, String language) { port().mergeSeries(id, format, key(language)); }
}
