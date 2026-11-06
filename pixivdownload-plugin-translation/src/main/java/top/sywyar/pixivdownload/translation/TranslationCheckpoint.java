package top.sywyar.pixivdownload.translation;

import top.sywyar.pixivdownload.ai.*;
import top.sywyar.pixivdownload.ai.model.*;
import top.sywyar.pixivdownload.translation.ai.*;
import top.sywyar.pixivdownload.translation.api.*;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.*;
import java.util.*;
import java.util.function.Supplier;

/** 顺序重放已确认响应，保留分段算法及 AI 术语累积的原有执行顺序。 */
final class TranslationCheckpoint {
    private final TranslationJobStore store;
    private final TranslationJobs.Job job;
    private final Runnable guard;
    private final String prefix;
    private final TranslationDocuments documents;
    private int aiIndex;
    private long lastProgress;
    String runId() { return job.runId; }
    TranslationCheckpoint(TranslationJobStore store, TranslationJobs.Job job, String prefix,
                          TranslationDocuments documents, Runnable guard) {
        this.store = store; this.job = job; this.prefix = prefix; this.documents = documents; this.guard = guard;
    }
    <T> T memo(String slot, String signature, Class<T> type, Supplier<T> action) {
        guard.run();
        String key = prefix + ":" + slot;
        var saved = store.step(job.id, key);
        if (saved != null) {
            if (!signature.equals(saved.signature())) throw new IllegalStateException("translation.checkpoint-conflict");
            return store.decode(saved.payload(), type);
        }
        T result = action.get();
        synchronized (job) {
            // 取消发生在远端响应返回前时，仍保存这份已付费结果；发布另行检查取消状态。
            boolean response = slot.startsWith("batch:") || slot.startsWith("ai:");
            if (response) job.savedSteps++;
            try { store.checkpoint(job, key, signature, store.encode(result)); }
            catch (RuntimeException failure) { if (response) job.savedSteps--; throw failure; }
            long now = System.nanoTime();
            if (response && now - lastProgress >= java.time.Duration.ofSeconds(10).toNanos()) {
                org.slf4j.LoggerFactory.getLogger(TranslationCheckpoint.class).info(
                        "Translation checkpoint saved: jobId={}, runId={}, savedSteps={}", job.id, job.runId, job.savedSteps);
                lastProgress = now;
            }
        }
        return result;
    }
    List<String> batch(String field, int index, List<String> input, Supplier<List<String>> request) {
        return Arrays.asList(memo("batch:" + field + ":" + index, TranslationDigest.of(store.encode(input)),
                String[].class, () -> {
                    var result = request.get();
                    if (result.size() != input.size() || result.stream().anyMatch(v -> v == null || v.isBlank()))
                        throw new IllegalStateException("translation.invalid-response");
                    return result.toArray(String[]::new);
                }));
    }
    AiChatClient ai(AiChatClient delegate, String fingerprint) {
        return new AiChatClient() {
            public boolean isConfigured() { return delegate.isConfigured(); }
            public AiChatResult chat(String type, List<AiChatMessage> messages, AiChatOptions options) {
                String signature = TranslationDigest.of(type, store.encode(messages), store.encode(options));
                return memo("ai:" + aiIndex++, signature, AiChatResult.class, () -> {
                    AiChatResult result;
                    try { result = delegate.chatBound(fingerprint, type, messages, options); }
                    catch (AiClientException e) { throw new IllegalStateException("translation.ai-request-failed", e); }
                    if ("length".equalsIgnoreCase(result.finishReason()))
                        throw new IllegalStateException("translation.incomplete-response");
                    try {
                        if (TranslationRequest.CALL_TYPE.equals(type)) {
                            var parsed = TranslationResponse.parse(result.content());
                            if (!parsed.ok() || parsed.text() == null || parsed.text().isBlank())
                                throw new IllegalArgumentException();
                        } else if (TitleTranslationRequest.CALL_TYPE.equals(type)) {
                            if (!TitleTranslationResponse.parse(result.content()).ok()) throw new IllegalArgumentException();
                        } else if (LangProbeRequest.CALL_TYPE.equals(type)) {
                            if (!LangProbeResponse.parse(result.content()).ok()) throw new IllegalArgumentException();
                        } else SourceLanguageProbeResponse.parse(result.content());
                    } catch (IllegalArgumentException e) { throw new IllegalStateException("translation.invalid-response", e); }
                    return result;
                });
            }
            public AiChatResult chatTest(String type, AiClientSettings settings, List<AiChatMessage> messages, AiChatOptions options) {
                throw new UnsupportedOperationException();
            }
        };
    }
    static String digest(TranslationDocuments.Content value) {
        return value == null ? TranslationDigest.of(null, null, null)
                : TranslationDigest.of(value.rawContent(), value.title(), value.description());
    }
    TranslationDocuments.Content source(Kind kind, long id) {
        return memo("source:" + kind + ":" + id, "source-v1", TranslationDocuments.Content.class, () -> documents.source(kind, id));
    }
    TranslationDocuments.Content previous(Kind kind, long id, String lang) {
        return memo("previous:" + kind + ":" + id + ":" + lang, "previous-v1", TranslationDocuments.Content.class,
                () -> documents.translation(kind, id, lang));
    }
    UUID publication(Kind kind, long id) {
        return UUID.nameUUIDFromBytes((job.id + ":" + kind + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void prepare(Kind kind, long id, String language) {
        guard.run();
        if (!digest(source(kind, id)).equals(digest(documents.source(kind, id))))
            throw new IllegalStateException("translation.source-changed");
        if (!digest(previous(kind, id, language)).equals(digest(documents.translation(kind, id, language))))
            throw new IllegalStateException("translation.target-changed");
    }
    TranslationDocuments documents() {
        return new TranslationDocuments((NovelTranslationDocuments) null) {
            @Override public TranslationDocuments snapshot(String engine) { return this; }
            @Override public Content source(Kind kind, long id) { return TranslationCheckpoint.this.source(kind, id); }
            @Override public Content translation(Kind kind, long id, String lang) { return previous(kind, id, lang); }
            @Override public boolean translated(long id, String lang, boolean body, boolean title, boolean description) {
                return translated(Kind.NOVEL, id, lang, body, title, description);
            }
            @Override public boolean translated(Kind kind, long id, String lang, boolean body, boolean title, boolean description) {
                Content value = previous(kind, id, lang);
                return value != null && (!body || nonBlank(value.rawContent())) && (!title || nonBlank(value.title()))
                        && (!description || nonBlank(value.description()));
            }
            @Override public List<Term> glossaryEntries(long id, int maximum) {
                return Arrays.asList(memo("glossary:" + id, "terms-v1", Term[].class,
                        () -> documents.glossaryEntries(id, maximum).toArray(Term[]::new)));
            }
            @Override public List<TitleReference> titleReferences(long id, String lang, int maximum) {
                return Arrays.asList(memo("references:" + id + ":" + lang, "titles-v1", TitleReference[].class,
                        () -> documents.titleReferences(id, lang, maximum).toArray(TitleReference[]::new)));
            }
            @Override public void save(Kind kind, long id, String lang, String body, String title, String description) {
                if (!new TranslationVariant(lang, job.engine).language().equals(new TranslationVariant(job.language, job.engine).language()))
                    throw new IllegalStateException("translation.language-changed");
                Publication publication = new Publication(publication(kind, id), digest(source(kind, id)),
                        digest(previous(kind, id, lang)), TranslationDigest.of(body, title, description));
                synchronized (job) {
                    guard.run();
                    documents.publish(kind, id, lang, body, title, description, publication);
                }
            }
            @Override public void mergeTerms(long id, String lang, List<Term> terms) { documents.mergeTerms(id, lang, terms); }
            @Override public Long defaultGlossary(long id) { return documents.defaultGlossary(id); }
        };
    }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
