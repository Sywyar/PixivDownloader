package top.sywyar.pixivdownload.novel.translation;

import top.sywyar.pixivdownload.translation.api.TranslationVariant;

import jakarta.annotation.PreDestroy;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGlossaryEntry;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 小说数据的跨插件边界；分片仅用于传输，最终保存仍使用原有完整译文事务。 */
public final class NovelTranslationDocumentAccess implements NovelTranslationDocuments {
    private static final int MAX_TRANSFERS = 64;
    private static final long MAX_HELD_CHARS = 64L * 1024 * 1024;
    private static final long IDLE_NANOS = java.time.Duration.ofMinutes(5).toNanos();
    private final java.util.function.LongSupplier clock;
    private final NovelDatabase novels;
    private final NovelGlossaryService glossaries;
    private final NovelMergeService merges;
    private final DatabaseAvailability database;
    private TranslationPublications publications;
    private final Map<UUID, Transfer> transfers = new ConcurrentHashMap<>();
    private final AtomicLong heldChars = new AtomicLong();
    private final Object admission = new Object();
    private boolean closed;

    public NovelTranslationDocumentAccess(NovelDatabase novels, NovelGlossaryService glossaries,
                                          NovelMergeService merges, DatabaseAvailability database) {
        this(novels, glossaries, merges, database, System::nanoTime);
    }

    public NovelTranslationDocumentAccess(NovelDatabase novels, NovelGlossaryService glossaries,
                                          NovelMergeService merges, DatabaseAvailability database,
                                          TranslationPublications publications) {
        this(novels, glossaries, merges, database);
        this.publications = java.util.Objects.requireNonNull(publications);
    }

    NovelTranslationDocumentAccess(NovelDatabase novels, NovelGlossaryService glossaries,
                                   NovelMergeService merges, DatabaseAvailability database,
                                   java.util.function.LongSupplier clock) {
        this.novels = novels;
        this.glossaries = glossaries;
        this.merges = merges;
        this.database = database;
        this.clock = clock;
    }

    private static final class Transfer {
        final Kind kind;
        final long id;
        final String language;
        final Map<Field, String> source;
        final Map<Field, Integer> lengths;
        final Map<Field, StringBuilder> output = new EnumMap<>(Field.class);
        final long reserved;
        boolean closed;
        long lastAccess;

        Transfer(Kind kind, long id, String language, Map<Field, String> source,
                 Map<Field, Integer> lengths) {
            this.kind = kind;
            this.id = id;
            this.language = language;
            this.source = source;
            this.lengths = lengths;
            this.reserved = lengths.values().stream().mapToLong(n -> Math.max(0, n)).sum();
            if (source == null) lengths.forEach((field, length) -> {
                if (length >= 0) output.put(field, new StringBuilder());
            });
        }
    }

    @Override
    public Document openSource(Kind kind, long id) {
        database.requireAvailable();
        if (kind == Kind.NOVEL) {
            var novel = novels.getNovel(id);
            return novel == null ? null : snapshot(kind, id, novel.seriesId(),
                    novel.rawContent(), novel.title(), novel.description());
        }
        if (kind != Kind.SERIES) throw new IllegalArgumentException("TRANSLATION_DOCUMENT_KIND");
        var series = novels.getSeries(id);
        return series == null ? null : snapshot(kind, id, id, null, series.title(), series.description());
    }

    @Override
    public Document openTranslation(Kind kind, long id, String language) {
        database.requireAvailable();
        requireLanguage(language);
        if (kind == null || id <= 0) throw new IllegalArgumentException("TRANSLATION_DOCUMENT_KIND");
        return kind == Kind.NOVEL
                ? snapshot(kind, id, null, novels.getTranslationContent(id, language),
                    novels.getTranslationTitle(id, language), novels.getTranslationDescription(id, language))
                : snapshot(kind, id, id, null, novels.getSeriesTitleTranslation(id, language),
                    novels.getSeriesDescriptionTranslation(id, language));
    }

    private Document snapshot(Kind kind, long id, Long seriesId, String body, String title, String description) {
        Map<Field, String> source = new EnumMap<>(Field.class);
        source.put(Field.BODY, body);
        source.put(Field.TITLE, title);
        source.put(Field.DESCRIPTION, description);
        Map<Field, Integer> lengths = lengths(size(body), size(title), size(description));
        UUID token = register(new Transfer(kind, id, null, source, lengths));
        return new Document(token, id, seriesId, size(body), size(title), size(description));
    }

    @Override
    public String read(UUID token, Field field, int offset, int length) {
        database.requireAvailable();
        Transfer transfer = require(token);
        synchronized (transfer) {
            ensureOpen(transfer);
            if (transfer.source == null || field == null || offset < 0 || length < 0 || length > CHUNK_CHARS)
                throw new IllegalArgumentException("TRANSLATION_READ_RANGE");
            String text = transfer.source.get(field);
            if (text == null || offset > text.length() || length > text.length() - offset)
                throw new IllegalArgumentException("TRANSLATION_READ_RANGE");
            return text.substring(offset, offset + length);
        }
    }

    @Override
    public boolean translated(Kind kind, long id, String language, boolean body, boolean title, boolean description) {
        database.requireAvailable();
        if (language == null || language.isBlank()) return false;
        if (kind == Kind.NOVEL) return (!body || nonBlank(novels.getTranslationContent(id, language)))
                && (!title || nonBlank(novels.getTranslationTitle(id, language)))
                && (!description || nonBlank(novels.getTranslationDescription(id, language)));
        if (kind != Kind.SERIES || body) throw new IllegalArgumentException("TRANSLATION_DOCUMENT_KIND");
        return (!title || nonBlank(novels.getSeriesTitleTranslation(id, language)))
                && (!description || nonBlank(novels.getSeriesDescriptionTranslation(id, language)));
    }

    @Override
    public UUID beginWrite(Kind kind, long id, String language, int body, int title, int description) {
        database.requireAvailable();
        requireLanguage(language);
        if (kind == null || id <= 0 || body < -1 || title < -1 || description < -1
                || (kind == Kind.SERIES && body != -1)) throw new IllegalArgumentException("TRANSLATION_WRITE_SHAPE");
        return register(new Transfer(kind, id, language, null, lengths(body, title, description)));
    }

    @Override
    public void append(UUID token, Field field, int offset, String text) {
        database.requireAvailable();
        Transfer transfer = require(token);
        synchronized (transfer) {
            ensureOpen(transfer);
            StringBuilder output = transfer.output.get(field);
            if (output == null || text == null || text.length() > CHUNK_CHARS || offset != output.length()
                    || text.length() > transfer.lengths.get(field) - offset)
                throw new IllegalArgumentException("TRANSLATION_WRITE_RANGE");
            output.append(text);
        }
    }

    @Override
    public void commit(UUID token) {
        database.requireAvailable();
        Transfer transfer = require(token);
        synchronized (transfer) {
            ensureOpen(transfer);
            if (transfer.source != null) throw new IllegalArgumentException("TRANSLATION_READ_ONLY");
            transfer.output.forEach((field, text) -> {
                if (text.length() != transfer.lengths.get(field))
                    throw new IllegalStateException("TRANSLATION_WRITE_INCOMPLETE");
            });
            String body = value(transfer, Field.BODY);
            String title = value(transfer, Field.TITLE);
            String description = value(transfer, Field.DESCRIPTION);
            if (transfer.kind == Kind.NOVEL) {
                if (body == null) novels.saveTranslationMetadata(transfer.id, transfer.language, title, description);
                else novels.saveTranslation(transfer.id, transfer.language, body, title, description);
            } else {
                novels.saveSeriesTitleTranslation(transfer.id, transfer.language, title, description);
            }
            close(token);
        }
    }

    @Override
    public PublicationResult publish(UUID token, Publication publication) {
        database.requireAvailable();
        if (publications == null) throw new IllegalStateException("TRANSLATION_PUBLICATION_UNAVAILABLE");
        Transfer transfer = require(token);
        synchronized (transfer) {
            ensureOpen(transfer);
            if (transfer.source != null) throw new IllegalArgumentException("TRANSLATION_READ_ONLY");
            transfer.output.forEach((field, text) -> {
                if (text.length() != transfer.lengths.get(field))
                    throw new IllegalStateException("TRANSLATION_WRITE_INCOMPLETE");
            });
            var result = publications.publish(transfer.kind, transfer.id, transfer.language,
                    value(transfer, Field.BODY), value(transfer, Field.TITLE), value(transfer, Field.DESCRIPTION), publication);
            close(token);
            return result;
        }
    }

    @Override public boolean published(UUID id) {
        database.requireAvailable();
        if (publications == null) throw new IllegalStateException("TRANSLATION_PUBLICATION_UNAVAILABLE");
        return publications.published(id);
    }

    @Override public List<Long> seriesNovelIds(long id, int offset, int limit) {
        database.requireAvailable();
        if (publications == null) throw new IllegalStateException("TRANSLATION_PUBLICATION_UNAVAILABLE");
        return publications.seriesNovelIds(id, offset, limit);
    }

    @Override
    public void close(UUID token) {
        if (token == null) return;
        Transfer transfer = transfers.get(token);
        if (transfer == null) return;
        synchronized (transfer) {
            if (transfers.remove(token, transfer)) {
                transfer.closed = true;
                heldChars.addAndGet(-transfer.reserved);
            }
        }
    }

    @Override
    public List<Term> glossaryEntries(long id, int offset, int limit) {
        database.requireAvailable();
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("TRANSLATION_PAGE_RANGE");
        return glossaries.entries(id, offset, limit).stream()
                .map(e -> new Term(e.source(), e.langCode(), e.target())).toList();
    }

    @Override
    public Long defaultGlossary(long id) { database.requireAvailable(); return glossaries.getOrCreateNovelDefaultId(id); }

    @Override
    public void mergeTerms(long id, String language, List<Term> terms) {
        database.requireAvailable();
        if (terms == null || terms.size() > 100) throw new IllegalArgumentException("TRANSLATION_PAGE_RANGE");
        glossaries.mergeAiTerms(id, language, terms.stream()
                .map(t -> new NovelGlossaryEntry(t.source(), t.langCode(), t.target())).toList());
    }

    @Override
    public List<TitleReference> titleReferences(long id, String language, int offset, int limit) {
        database.requireAvailable();
        if (offset < 0 || limit < 1 || limit > 80) throw new IllegalArgumentException("TRANSLATION_PAGE_RANGE");
        List<TitleReference> result = new ArrayList<>();
        int skipped = 0;
        for (var novel : novels.getNovelsBySeriesId(id)) {
            if (!nonBlank(novel.title())) continue;
            String translated = novels.getTranslationTitle(novel.novelId(), language);
            if (!nonBlank(translated)) continue;
            if (skipped++ < offset) continue;
            result.add(new TitleReference(novel.title(), translated));
            if (result.size() == limit) break;
        }
        return result;
    }

    @Override
    public void mergeSeries(long id, String format, String variantKey) {
        database.requireAvailable();
        if (variantKey != null) requireLanguage(variantKey);
        try {
            var result = merges.mergeVariant(id, NovelDownloadService.NovelFormat.parse(format), variantKey);
            if (!result.success()) throw new IllegalStateException("TRANSLATION_MERGE_FAILED");
        }
        catch (java.io.IOException failure) { throw new IllegalStateException("TRANSLATION_MERGE_FAILED", failure); }
    }

    private UUID register(Transfer transfer) {
        synchronized (admission) {
            // 消费者撤回后其代理无法再调用 close；新传输按空闲时间回收遗留分片。
            long now = clock.getAsLong();
            transfers.forEach((token, pending) -> {
                synchronized (pending) {
                    if (now - pending.lastAccess > IDLE_NANOS) close(token);
                }
            });
            if (closed || transfers.size() >= MAX_TRANSFERS || transfer.reserved > MAX_HELD_CHARS - heldChars.get())
                throw new IllegalStateException("TRANSLATION_TRANSFER_CAPACITY");
            UUID token = UUID.randomUUID();
            transfer.lastAccess = now;
            heldChars.addAndGet(transfer.reserved);
            transfers.put(token, transfer);
            return token;
        }
    }

    private Transfer require(UUID token) {
        Transfer transfer = token == null ? null : transfers.get(token);
        if (transfer == null) throw new IllegalStateException("TRANSLATION_TRANSFER_UNAVAILABLE");
        return transfer;
    }
    private void ensureOpen(Transfer transfer) {
        long now = clock.getAsLong();
        if (transfer.closed || now - transfer.lastAccess > IDLE_NANOS)
            throw new IllegalStateException("TRANSLATION_TRANSFER_UNAVAILABLE");
        transfer.lastAccess = now;
    }
    private static void requireLanguage(String language) {
        TranslationVariant.parse(language);
    }
    private static Map<Field, Integer> lengths(int body, int title, int description) {
        return Map.of(Field.BODY, body, Field.TITLE, title, Field.DESCRIPTION, description);
    }
    private static int size(String value) { return value == null ? -1 : value.length(); }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static String value(Transfer transfer, Field field) {
        StringBuilder value = transfer.output.get(field);
        return value == null ? null : value.toString();
    }
    @PreDestroy
    public void shutdown() {
        synchronized (admission) { closed = true; }
        List.copyOf(transfers.keySet()).forEach(this::close);
    }
}
