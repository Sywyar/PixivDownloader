package top.sywyar.pixivdownload.translation;

import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.Kind;
import top.sywyar.pixivdownload.translation.api.NovelTranslationOperations.Engine;
import top.sywyar.pixivdownload.translation.AiNovelTranslationService.Result;
import top.sywyar.pixivdownload.translation.AiNovelTranslationService.Status;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/** 引擎选择与译文保存；AI 提示词与普通翻译 API 各自保持独立。 */
@Slf4j
public class NovelTranslationService {
    private final TranslationConfig config;
    private final AiChatClient ai;
    private final TranslationDocuments documents;
    private final MachineTranslationClient machine;
    private final MessageResolver messages;
    private final DatabaseAvailability database;
    private TranslationCheckpoint checkpoint;

    public NovelTranslationService(TranslationConfig config, AiChatClient ai, TranslationDocuments documents,
                                   MachineTranslationClient machine, MessageResolver messages, DatabaseAvailability database) {
        this.config = config; this.ai = ai; this.documents = documents;
        this.machine = machine; this.messages = messages; this.database = database;
    }
    NovelTranslationService checkpointed(TranslationCheckpoint context, String fingerprint) {
        var service = new NovelTranslationService(config, context.ai(ai, fingerprint), context.documents(), machine, messages, database);
        service.checkpoint = context;
        return service;
    }
    NovelTranslationService bind(String engine) {
        if (!engine.equals("ai")) return this;
        return new NovelTranslationService(config, ai.bind().orElseThrow(() -> new IllegalStateException("translation.unavailable")),
                documents, machine, messages, database);
    }
    String fingerprint(String engine) {
        if (engine.equals("ai")) return ai.configurationFingerprint()
                .orElseThrow(() -> new IllegalStateException("translation.configuration-unavailable"));
        var settings = config.machine(engine);
        return top.sywyar.pixivdownload.translation.api.TranslationDigest.of("machine-v1", engine,
                engine.equals("libretranslate") ? config.getLibretranslateUrl() : "https://openapi.youdao.com/v2/api",
                String.valueOf(settings.getRequestChars()), String.valueOf(settings.getBatchSize()));
    }
    String executionFingerprint(String engine) {
        return top.sywyar.pixivdownload.translation.api.TranslationDigest.of("translation-algorithm-v1", fingerprint(engine),
                String.valueOf(engine.equals("ai") ? config.getAiSegmentSize() : config.machine(engine).getRequestChars()));
    }
    public String engine() { return engine(null); }
    String engine(String requested) {
        String id = requested == null || requested.isBlank() ? config.getEngine() : requested;
        if (!List.of("ai", "youdao", "libretranslate").contains(id)) throw new IllegalArgumentException("translation.unknown-engine");
        return id;
    }
    public boolean isAvailable() { return isAvailable(engine()); }
    public boolean isAvailable(String engine) { return engine.equals("ai") ? ai.isConfigured() : machine.available(engine); }
    public boolean supportsGlossary() { return engine().equals("ai"); }
    public int defaultSegmentSize() { return engine().equals("ai") ? config.getAiSegmentSize() : config.machine(engine()).getRequestChars(); }
    public int segmentLimit() { return engine().equals("ai") ? 1_000_000 : config.machine(engine()).getRequestChars(); }
    public List<MachineTranslationClient.Language> languages(String requested) {
        String selected = engine(requested);
        return observe("languages", 0, selected, "", run -> machine.languages(selected));
    }
    public List<Engine> engines() {
        return List.of(new Engine("ai", "translation.engine.ai", ai.isConfigured(), true, true),
                new Engine("youdao", "translation.engine.youdao", machine.available("youdao"), false, false),
                new Engine("libretranslate", "translation.engine.libretranslate", machine.available("libretranslate"), false, false));
    }
    private AiNovelTranslationService ai(TranslationDocuments session) {
        return new AiNovelTranslationService(ai, session, messages, database);
    }
    public String resolveLangCode(String target) { return resolveLangCode(target, null); }
    public String resolveLangCode(String target, String requested) {
        database.requireAvailable();
        return engine(requested).equals("ai") ? ai(documents).resolveLangCode(target) : MachineTranslationClient.language(target);
    }
    public Result translateChapter(long id, String target, int size, boolean overwrite, String hint, Long glossary,
                                   boolean body, boolean title, boolean description) {
        return translateChapter(id, target, size, overwrite, hint, glossary, body, title, description, null);
    }
    public Result translateChapter(long id, String target, int size, boolean overwrite, String hint, Long glossary,
                                   boolean body, boolean title, boolean description, String requested) {
        database.requireAvailable();
        String engine = engine(requested);
        if (engine.equals("ai")) return ai(documents.snapshot(engine)).translateChapter(id, target,
                size <= 0 ? config.getAiSegmentSize() : size, overwrite, hint, glossary, body, title, description);
        String lang = MachineTranslationClient.language(target);
        try {
            return observe("novel", id, engine, lang, run -> translateMachineChapter(
                    id, lang, size, overwrite, glossary, body, title, description, engine, run));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            database.requireAvailable();
            if (checkpoint != null) throw failure;
            return result(Status.ERROR, lang, errorKey(failure));
        }
    }
    private Result translateMachineChapter(long id, String lang, int size, boolean overwrite, Long glossary,
                                           boolean body, boolean title, boolean description, String engine, String run) {
        var session = documents.snapshot(engine);
        if (!body && !title && !description) throw new IllegalArgumentException("novel.translate.no-scope");
        if (glossary != null) throw new IllegalArgumentException("translation.glossary-unavailable");
        if (lang.isBlank()) return result(Status.INVALID_LANGUAGE, null, "translation.language-code-required");
        var source = session.getNovel(id);
        if (source == null) return result(Status.NOT_FOUND, lang, "novel.translate.not-found");
        if (!overwrite && session.translated(id, lang, body, title, description)) return result(Status.SKIPPED, lang, "novel.translate.skipped");
        if (body && (source.rawContent() == null || source.rawContent().isBlank())) return result(Status.EMPTY, lang, "novel.translate.empty");
        String translatedBody = body ? translate(engine, source.rawContent(), lang, size, run, "body") : null;
        String translatedTitle = title ? translate(engine, source.title(), lang, size, run, "title") : null;
        String translatedDescription = description ? translate(engine, source.description(), lang, size, run, "description") : null;
        database.requireAvailable();
        log.info("Translation stage: runId={}, stage=save", run);
        session.saveTranslation(id, lang, translatedBody, translatedTitle, translatedDescription);
        return result(Status.OK, lang, "novel.translate.success");
    }
    private String translate(String engine, String text, String lang, int size, String run, String field) {
        database.requireAvailable();
        log.info("Translation stage: runId={}, stage={}, inputChars={}", run, field, text == null ? 0 : text.length());
        String result = checkpoint == null ? machine.translate(engine, text, lang, size, run, field)
                : machine.translate(engine, text, lang, size, run, field, checkpoint);
        database.requireAvailable();
        return result;
    }
    public String translateSeriesTitle(long id, String target, String hint, Long glossary, boolean title, boolean description) {
        return translateSeriesTitle(id, target, hint, glossary, title, description, null);
    }
    public String translateSeriesTitle(long id, String target, String hint, Long glossary, boolean title, boolean description, String requested) {
        database.requireAvailable();
        String engine = engine(requested);
        if (engine.equals("ai")) return ai(documents.snapshot(engine)).translateSeriesTitle(id, target, hint, glossary, title, description);
        String lang = MachineTranslationClient.language(target);
        return observe("series", id, engine, lang, run -> translateMachineSeries(id, lang, glossary, title, description, engine, run));
    }
    private String translateMachineSeries(long id, String lang, Long glossary, boolean title, boolean description,
                                          String engine, String run) {
        var session = documents.snapshot(engine);
        if (glossary != null) throw new IllegalArgumentException("translation.glossary-unavailable");
        if (lang.isBlank() || (!title && !description)) return "";
        var source = session.getSeries(id);
        if (source == null) return "";
        if (session.translated(Kind.SERIES, id, lang, false, title, description)) {
            log.info("Translation stage: runId={}, stage=skipped, reason=already_translated", run);
            return lang;
        }
        String savedTitle = title ? translate(engine, source.title(), lang, 0, run, "title") : session.getSeriesTitleTranslation(id, lang);
        if (savedTitle == null || savedTitle.isBlank()) savedTitle = source.title();
        String savedDescription = description ? translate(engine, source.description(), lang, 0, run, "description") : null;
        log.info("Translation stage: runId={}, stage=save", run);
        session.saveSeriesTitleTranslation(id, lang, savedTitle, savedDescription);
        return lang;
    }
    private Result result(Status status, String lang, String key) { return new Result(status, lang, messages.get(key), false); }

    private <T> T observe(String action, long id, String engine, String lang, Function<String, T> operation) {
        String run = checkpoint == null ? UUID.randomUUID().toString() : checkpoint.runId();
        long started = System.nanoTime();
        log.info("Translation started: runId={}, action={}, documentId={}, engine={}, target={}", run, action, id, engine, lang);
        try {
            T value = operation.apply(run);
            String outcome = value instanceof Result result ? result.status().name()
                    : value instanceof String text && text.isBlank() || value instanceof List<?> list && list.isEmpty() ? "EMPTY" : "OK";
            log.info("Translation finished: runId={}, action={}, documentId={}, engine={}, outcome={}, durationMs={}",
                    run, action, id, engine, outcome, (System.nanoTime() - started) / 1_000_000);
            return value;
        } catch (RuntimeException failure) {
            MachineTranslationClient.Failure remote = failure instanceof MachineTranslationClient.Failure f ? f : null;
            var event = failure instanceof IllegalArgumentException || "translation.unavailable".equals(errorKey(failure))
                    ? log.atWarn() : log.atError();
            event.log("Translation failed: runId={}, action={}, documentId={}, engine={}, target={}, reason={}, httpStatus={}, providerCode={}, exceptionType={}, durationMs={}",
                    run, action, id, engine, lang, remote == null ? errorKey(failure) : remote.reason,
                    remote == null ? 0 : remote.httpStatus, remote == null ? "none" : remote.providerCode,
                    failure.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            throw failure;
        }
    }
    static String errorKey(RuntimeException failure) {
        if (failure instanceof MachineTranslationClient.Failure) return failure.getMessage();
        return switch (String.valueOf(failure.getMessage())) {
            case "translation.unavailable", "translation.invalid-url", "translation.response-too-large",
                 "translation.invalid-response", "translation.language-unsupported", "translation.glossary-unavailable",
                 "novel.translate.no-scope" -> failure.getMessage();
            default -> "translation.remote-error";
        };
    }
}
