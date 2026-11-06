package top.sywyar.pixivdownload.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;
import top.sywyar.pixivdownload.translation.api.*;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.Kind;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("持久翻译任务的检查点与恢复")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class TranslationJobsTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final TranslationConfig config = new TranslationConfig();
    final TranslationDocuments documents = mock(TranslationDocuments.class);
    final RestTemplate http = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    final Set<UUID> receipts = new HashSet<>();
    TranslationJobStore store;
    NovelTranslationService translations;
    String publishedBody;
    final Map<Path, String> postgresSchemas = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        config.setEngine("libretranslate"); config.setLibretranslateUrl("http://localhost:5000");
        config.getLibretranslate().setRequestChars(3); config.getLibretranslate().setBatchSize(1);
        config.getLibretranslate().setMaxRetries(0);
        when(documents.snapshot(anyString())).thenReturn(documents);
        when(documents.source(Kind.NOVEL, 7)).thenReturn(new TranslationDocuments.Content(7, null, "abc\ndef\nghi", "source", "description"));
        when(documents.getNovel(7)).thenAnswer(call -> documents.source(Kind.NOVEL, 7));
        when(documents.translation(Kind.NOVEL, 7, "zh-CN")).thenReturn(new TranslationDocuments.Content(7, null, "old", "old title", null));
        when(documents.published(any())).thenAnswer(call -> receipts.contains(call.getArgument(0)));
        doAnswer(call -> {
            publishedBody = call.getArgument(3);
            receipts.add(((NovelTranslationDocuments.Publication) call.getArgument(6)).id());
            return null;
        }).when(documents).publish(any(), anyLong(), anyString(), any(), any(), any(), any());
        translations = new NovelTranslationService(config, mock(AiChatClient.class), documents,
                new MachineTranslationClient(config, http, http, json), mock(MessageResolver.class), () -> true);
        store = new TranslationJobStore(database(directory.resolve("jobs.db")), json);
    }
    private PluginDatabase database(Path path) throws Exception {
        String url = "jdbc:sqlite:" + path;
        var credentials = new Properties();
        DatabaseConnectionTarget target = new SqliteDatabasePlugin().resolve(new DatabaseConnectionSettings("", "", "", path));
        if ("postgresql".equals(System.getProperty("translation.test.database"))) {
            String base = Objects.requireNonNull(System.getenv("PIXIV_TEST_POSTGRES_URL"));
            credentials.setProperty("user", Objects.requireNonNull(System.getenv("PIXIV_TEST_POSTGRES_USER")));
            credentials.setProperty("password", Objects.requireNonNull(System.getenv("PIXIV_TEST_POSTGRES_PASSWORD")));
            String owner = postgresSchemas.computeIfAbsent(path, ignored -> "translation_job_" + UUID.randomUUID().toString().replace("-", ""));
            try (var c = DriverManager.getConnection(base, credentials); var statement = c.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + owner);
            }
            url = base + "?currentSchema=" + owner;
            target = new top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin().resolve(
                    new DatabaseConnectionSettings(url, credentials.getProperty("user"), credentials.getProperty("password"), path));
        }
        final String connectionUrl = url;
        final DatabaseConnectionTarget provider = target;
        var schema = new MigrationSchema(1, TranslationJobStore.schema().tables(), List.of(), List.of());
        try (var c = DriverManager.getConnection(connectionUrl, credentials)) {
            c.setAutoCommit(false); target.initializeStorage(c, schema); c.commit();
        }
        return new PluginDatabase() {
            public boolean available() { return true; }
            public String databaseId() { return "fixture"; }
            public <T> T read(DatabaseSession.Work<T> work) throws SQLException { return transaction(work); }
            public synchronized <T> T transaction(DatabaseSession.Work<T> work) throws SQLException {
                try (var c = DriverManager.getConnection(connectionUrl, credentials)) {
                    c.setAutoCommit(false);
                    try (var s = provider.storageSession(c, schema)) {
                        try {
                            T result = work.run(s);
                            if (s.rollbackOnly()) throw new SQLException("rollback");
                            c.commit(); return result;
                        } catch (SQLException | RuntimeException | Error error) { c.rollback(); throw error; }
                    }
                }
            }
        };
    }
    @AfterEach void dropPostgresFixtures() throws Exception {
        if (postgresSchemas.isEmpty()) return;
        try (var c = DriverManager.getConnection(System.getenv("PIXIV_TEST_POSTGRES_URL"),
                System.getenv("PIXIV_TEST_POSTGRES_USER"), System.getenv("PIXIV_TEST_POSTGRES_PASSWORD"));
             var statement = c.createStatement()) {
            for (String schema : postgresSchemas.values()) statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
    private TranslationJobs jobs() {
        return new TranslationJobs(store, translations, documents, new SyncTaskExecutor(), new QueueTaskTracker("translation"));
    }
    private TranslationJobs.Submission request() {
        return new TranslationJobs.Submission(UUID.randomUUID(), new NovelTranslationOperations.Request(7, "zh-CN", 0,
                true, null, null, true, false, false, "libretranslate"), null, false, false, null);
    }
    private void response(String text) {
        server.expect(requestTo("http://localhost:5000/translate"))
                .andRespond(withSuccess("{\"translatedText\":\"" + text + "\"}", MediaType.APPLICATION_JSON));
    }
    private void fail() { server.expect(requestTo("http://localhost:5000/translate")).andRespond(withServerError()); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,false", "false,true,false", "true,true,false", "false,false,true"})
    @DisplayName("所选简介缺失时不保存无效响应，继续后重新请求并完整发布")
    void retriesMissingRequestedDescription(boolean body, boolean title, boolean savedInvalidResponse,
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var ai = mock(AiChatClient.class);
        when(ai.isConfigured()).thenReturn(true);
        when(ai.bind()).thenReturn(Optional.of(ai));
        when(ai.configurationFingerprint()).thenReturn(Optional.of("fixture-model"));
        config.setEngine("ai");
        config.setAiSegmentSize(0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var signature = new java.util.concurrent.atomic.AtomicReference<String>();
        when(ai.chatBound(anyString(), anyString(), anyList(), any())).thenAnswer(call -> {
            String type = call.getArgument(1);
            signature.set(TranslationDigest.of(type, store.encode(call.getArgument(2)), store.encode(call.getArgument(3))));
            String content;
            if (type.equals(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE))
                content = "{\"code\":\"zh-CN\",\"valid\":true}";
            else if (type.equals(top.sywyar.pixivdownload.translation.ai.SourceLanguageProbeRequest.CALL_TYPE))
                content = "{\"same\":false}";
            else content = "{\"status\":\"ok\",\"lang\":\"zh-CN\",\"title\":\"translated title\",\"text\":\"translated body\""
                    + (calls.incrementAndGet() == 1 ? "}" : ",\"description\":\"translated description\"}");
            return new top.sywyar.pixivdownload.ai.model.AiChatResult(content, "stop", null, null, null);
        });
        translations = new NovelTranslationService(config, ai, documents,
                new MachineTranslationClient(config, http, http, json), mock(MessageResolver.class), () -> true);
        var submitted = jobs().submit(new TranslationJobs.Submission(UUID.randomUUID(),
                new NovelTranslationOperations.Request(7, "zh-CN", 0, true, null, null, body, title, true, "ai"),
                null, false, false, null));
        assertThat(submitted.state()).isEqualTo("PAUSED");
        assertThat(submitted.savedSteps()).isEqualTo(body ? 2 : 1);
        verify(documents, never()).publish(any(), anyLong(), anyString(), any(), any(), any(), any());
        if (savedInvalidResponse) {
            new TranslationCheckpoint(store, store.get(submitted.id()), "novel:7", documents, () -> {})
                    .memo("ai:0", signature.get(), top.sywyar.pixivdownload.ai.model.AiChatResult.class,
                            () -> new top.sywyar.pixivdownload.ai.model.AiChatResult(
                                    "{\"status\":\"ok\",\"lang\":\"zh-CN\",\"title\":\"translated title\"}", "stop", null, null, null));
        }
        var resumed = jobs().resume(submitted.id());
        assertThat(resumed.state()).isEqualTo("COMPLETED");
        assertThat(resumed.savedSteps()).isEqualTo(body ? 3 : 2);
        assertThat(calls).hasValue(2);
        if (savedInvalidResponse) assertThat(output.getAll())
                .contains("Translation checkpoint rejected:", "reason=translation.invalid-response")
                .doesNotContain("translated title", "translated description", "translated body");
        verify(documents).publish(eq(Kind.NOVEL), eq(7L), eq("zh-CN"),
                eq(body ? "translated body" : null), eq(title ? "translated title" : null),
                eq("translated description"), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"metadata,false", "metadata,true", "body,false", "body,true", "later,false", "later,true"})
    @DisplayName("每段响应及元数据语言必须与任务一致，继续时剔除错语种检查点且复用有效分段")
    void rejectsWrongResponseLanguage(String scope, boolean savedInvalidResponse) throws Exception {
        var ai = configuredAi();
        boolean body = !scope.equals("metadata");
        boolean later = scope.equals("later");
        config.setAiSegmentSize(3);
        when(documents.source(Kind.NOVEL, 7)).thenReturn(new TranslationDocuments.Content(
                7, null, later ? "abc\ndef\nghi" : "abc", "source", "description"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var signature = new java.util.concurrent.atomic.AtomicReference<String>();
        var invalid = new java.util.concurrent.atomic.AtomicReference<top.sywyar.pixivdownload.ai.model.AiChatResult>();
        when(ai.chatBound(anyString(), anyString(), anyList(), any())).thenAnswer(call -> {
            String type = call.getArgument(1);
            String content;
            if (type.equals(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE))
                content = "{\"code\":\"zh-CN\",\"valid\":true}";
            else if (type.equals(top.sywyar.pixivdownload.translation.ai.SourceLanguageProbeRequest.CALL_TYPE))
                content = "{\"same\":false}";
            else {
                int n = calls.incrementAndGet();
                boolean wrong = n == (later ? 2 : 1);
                content = json.writeValueAsString(Map.of("status", "ok", "lang", wrong ? "ja-JP" : "zh-CN",
                        "text", wrong ? "WRONG-LANGUAGE" : "GOOD-" + n, "title", "title", "description", "description"));
                if (wrong) {
                    signature.set(TranslationDigest.of(type, store.encode(call.getArgument(2)), store.encode(call.getArgument(3))));
                    invalid.set(aiResponse(content));
                }
            }
            return aiResponse(content);
        });
        var submitted = jobs().submit(new TranslationJobs.Submission(UUID.randomUUID(),
                new NovelTranslationOperations.Request(7, "zh-CN", 3, true, null, null, body, false, !body, "ai"),
                null, false, false, null));
        assertThat(submitted.state()).isEqualTo("PAUSED");
        assertThat(submitted.reason()).isEqualTo("translation.language-changed");
        assertThat(submitted.savedSteps()).isEqualTo(body ? later ? 3 : 2 : 1);
        verify(documents, never()).publish(any(), anyLong(), anyString(), any(), any(), any(), any());
        if (savedInvalidResponse) new TranslationCheckpoint(store, store.get(submitted.id()), "novel:7", documents, () -> {})
                .memo("ai:" + (body ? later ? 2 : 1 : 0), signature.get(),
                        top.sywyar.pixivdownload.ai.model.AiChatResult.class, invalid::get);
        var resumed = jobs().resume(submitted.id());
        assertThat(resumed.state()).isEqualTo("COMPLETED");
        assertThat(calls).hasValue(later ? 4 : 2);
        assertThat(resumed.savedSteps()).isEqualTo(body ? later ? 5 : 3 : 2);
        if (body) assertThat(publishedBody).isEqualTo(later ? "GOOD-1\nGOOD-3\nGOOD-4" : "GOOD-2");
        else verify(documents).publish(eq(Kind.NOVEL), eq(7L), eq("zh-CN"), isNull(), isNull(), eq("description"), any());
        verify(ai, times(1)).chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE), anyList(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"novel", "series", "metadata"})
    @DisplayName("仅翻译简介时跳过空源字段，系列继续下一章且空系列元数据不阻塞")
    void skipsEmptySelectedDescription(String scope) throws Exception {
        var ai = configuredAi();
        when(ai.chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE), anyList(), any()))
                .thenReturn(aiResponse("{\"code\":\"zh-CN\",\"valid\":true}"));
        when(ai.chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.TitleTranslationRequest.CALL_TYPE), anyList(), any()))
                .thenAnswer(call -> aiResponse("{\"status\":\"ok\",\"lang\":\"zh-CN\",\"title\":\"title\""
                        + (json.writeValueAsString(call.getArgument(2)).contains("fixture-novel-8-description")
                        ? ",\"description\":\"translated description\"}" : "}")));
        when(documents.source(Kind.NOVEL, 7)).thenReturn(new TranslationDocuments.Content(7, 9L, "abc", "source", null));
        when(documents.source(Kind.NOVEL, 8)).thenReturn(new TranslationDocuments.Content(8, 9L, "def", "source", "fixture-novel-8-description"));
        when(documents.source(Kind.SERIES, 9)).thenReturn(new TranslationDocuments.Content(9, 9L, null, "series title", " \t"));
        when(documents.seriesNovelIds(9, 0, 100)).thenReturn(List.of(7L, 8L));
        when(documents.seriesNovelIds(9, 2, 100)).thenReturn(List.of());
        boolean series = scope.equals("series"), metadata = scope.equals("metadata");
        var result = jobs().submit(new TranslationJobs.Submission(UUID.randomUUID(),
                new NovelTranslationOperations.Request(metadata || series ? 0 : 7, "zh-CN", 0, true, null, null,
                        false, false, true, "ai"), series || metadata ? 9L : null, series, series, "epub", metadata));
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.completed()).isEqualTo(series ? 2 : metadata ? 0 : 1);
        assertThat(result.outcome()).isEqualTo(series ? "OK" : "EMPTY");
        assertThat(result.skipped()).isEqualTo(metadata ? 0 : 1);
        verify(ai, times(series ? 1 : 0)).chatBound(anyString(),
                eq(top.sywyar.pixivdownload.translation.ai.TitleTranslationRequest.CALL_TYPE), anyList(), any());
        if (series) {
            verify(documents).publish(eq(Kind.NOVEL), eq(8L), eq("zh-CN"), isNull(), isNull(), eq("translated description"), any());
            verify(documents).mergeSeries(9, "epub", "zh-CN");
        }
        verify(documents, never()).publish(eq(Kind.NOVEL), eq(7L), anyString(), any(), any(), any(), any());
        verify(documents, never()).publish(eq(Kind.SERIES), anyLong(), anyString(), any(), any(), any(), any());
    }

    static java.util.stream.Stream<String> probeCodes() {
        return java.util.stream.Stream.concat(invalidProbeCodes(), java.util.stream.Stream.of("zh-CN"));
    }

    static java.util.stream.Stream<String> invalidProbeCodes() {
        return java.util.stream.Stream.of("zh-CN@ai", "zh-" + (char) 1 + "CN", "x".repeat(101));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("probeCodes")
    @DisplayName("异步语言探测拒绝非法身份，暂停任务可查询并在继续后重新探测")
    void languageProbePreservesReadableRecoverableJob(String firstCode) throws Exception {
        var ai = configuredAi();
        var languageCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE), anyList(), any()))
                .thenAnswer(call -> aiResponse(json.writeValueAsString(Map.of("valid", true,
                        "code", languageCalls.incrementAndGet() == 1 ? firstCode : "zh-CN"))));
        when(ai.chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.TitleTranslationRequest.CALL_TYPE), anyList(), any()))
                .thenReturn(aiResponse("{\"status\":\"ok\",\"lang\":\"zh-CN\",\"title\":\"translated title\"}"));
        when(documents.translation(eq(Kind.NOVEL), eq(7L), anyString())).thenAnswer(call -> {
            new TranslationVariant(call.getArgument(2), "ai");
            return new TranslationDocuments.Content(7, null, "old", "old title", null);
        });
        var pending = new ArrayList<Runnable>();
        var jobs = new TranslationJobs(store, translations, documents, pending::add, new QueueTaskTracker("translation"));
        var submitted = jobs.submit(metadataRequest());
        assertThat(submitted.state()).isEqualTo("QUEUED");
        pending.removeFirst().run();
        boolean valid = "zh-CN".equals(firstCode);
        var initial = jobs.get(submitted.id());
        assertThat(initial.state()).isEqualTo(valid ? "COMPLETED" : "PAUSED");
        assertThat(jobs.find(7, null)).extracting(TranslationJobs.View::id).containsExactly(submitted.id());
        if (!valid) {
            assertThat(initial.reason()).isEqualTo("translation.invalid-response");
            assertThat(initial.language()).isNull();
            assertThat(initial.variantKey()).isNull();
            assertThat(initial.savedSteps()).isZero();
            assertThat(store.get(submitted.id()).language).isNull();
            assertThat(store.step(submitted.id(), "plan:ai:0")).isNull();
            assertThat(store.step(submitted.id(), "plan:language")).isNull();
            verify(documents, never()).publish(any(), anyLong(), anyString(), any(), any(), any(), any());
            assertThat(jobs.resume(submitted.id()).state()).isEqualTo("QUEUED");
            pending.removeFirst().run();
        }
        assertThat(jobs.get(submitted.id()).state()).isEqualTo("COMPLETED");
        assertThat(jobs.get(submitted.id()).variantKey()).isEqualTo("zh-CN@ai");
        assertThat(jobs.get(submitted.id()).savedSteps()).isEqualTo(2);
        assertThat(languageCalls).hasValue(valid ? 1 : 2);
        verify(documents).publish(eq(Kind.NOVEL), eq(7L), eq("zh-CN"), isNull(), eq("translated title"), isNull(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidProbeCodes")
    @DisplayName("已有非法任务语言不破坏查询，继续后清理错误计划与响应且计数不减错")
    void repairsPersistedInvalidLanguage(String code) throws Exception {
        var ai = configuredAi();
        var languageCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE), anyList(), any()))
                .thenAnswer(call -> { languageCalls.incrementAndGet(); return aiResponse("{\"valid\":true,\"code\":\"zh-CN\"}"); });
        when(ai.chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.TitleTranslationRequest.CALL_TYPE), anyList(), any()))
                .thenReturn(aiResponse("{\"status\":\"ok\",\"lang\":\"zh-CN\",\"title\":\"translated title\"}"));
        var submitted = metadataRequest();
        var paused = new TranslationJobs.Job();
        paused.id = submitted.requestId(); paused.submission = submitted; paused.state = "PAUSED";
        paused.reason = "translation.remote-error"; paused.engine = "ai"; paused.language = code;
        paused.fingerprint = translations.executionFingerprint("ai"); paused.aiFingerprint = translations.fingerprint("ai");
        paused.queueKey = "novel:7"; paused.novelIds = new ArrayList<>(List.of(7L));
        paused.startedAt = paused.updatedAt = System.currentTimeMillis();
        store.create(paused);
        var plan = new TranslationCheckpoint(store, paused, "plan", documents, () -> {});
        var messages = new top.sywyar.pixivdownload.translation.ai.LangProbeRequest("zh-CN").toMessages();
        var options = top.sywyar.pixivdownload.ai.model.AiChatOptions.json().withTemperature(0.0);
        plan.memo("ai:0", TranslationDigest.of(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE,
                        store.encode(messages), store.encode(options)), top.sywyar.pixivdownload.ai.model.AiChatResult.class,
                () -> aiResponse(store.encode(Map.of("valid", true, "code", code))));
        plan.memo("language", "language-v1", String.class, () -> code);
        var pending = new ArrayList<Runnable>();
        var jobs = new TranslationJobs(store, translations, documents, pending::add, new QueueTaskTracker("translation"));
        var initial = jobs.get(paused.id);
        assertThat(initial.state()).isEqualTo("PAUSED");
        assertThat(initial.language()).isNull();
        assertThat(initial.variantKey()).isNull();
        assertThat(jobs.find(7, null)).extracting(TranslationJobs.View::id).containsExactly(paused.id);
        assertThat(store.get(paused.id).language).isEqualTo(code);
        assertThat(initial.savedSteps()).isEqualTo(1);
        assertThat(languageCalls).hasValue(0);
        assertThat(jobs.resume(paused.id).state()).isEqualTo("QUEUED");
        pending.removeFirst().run();
        var complete = jobs.get(paused.id);
        assertThat(complete.state()).isEqualTo("COMPLETED");
        assertThat(complete.language()).isEqualTo("zh-CN");
        assertThat(complete.variantKey()).isEqualTo("zh-CN@ai");
        assertThat(complete.savedSteps()).isEqualTo(2);
        assertThat(languageCalls).hasValue(1);
        assertThat(store.get(paused.id).language).isEqualTo("zh-CN");
        assertThat(jobs.find(7, null)).extracting(TranslationJobs.View::state).containsExactly("COMPLETED");
        verify(documents).publish(eq(Kind.NOVEL), eq(7L), eq("zh-CN"), isNull(), eq("translated title"), isNull(), any());
    }

    private TranslationJobs.Submission metadataRequest() {
        return new TranslationJobs.Submission(UUID.randomUUID(), new NovelTranslationOperations.Request(
                7, "zh-CN", 0, true, null, null, false, true, false, "ai"), null, false, false, null);
    }

    private AiChatClient configuredAi() {
        var ai = mock(AiChatClient.class);
        when(ai.isConfigured()).thenReturn(true);
        when(ai.bind()).thenReturn(Optional.of(ai));
        when(ai.configurationFingerprint()).thenReturn(Optional.of("fixture-model"));
        config.setEngine("ai");
        translations = new NovelTranslationService(config, ai, documents,
                new MachineTranslationClient(config, http, http, json), mock(MessageResolver.class), () -> true);
        return ai;
    }

    private static top.sywyar.pixivdownload.ai.model.AiChatResult aiResponse(String content) {
        return new top.sywyar.pixivdownload.ai.model.AiChatResult(content, "stop", null, null, null);
    }

    @Test @DisplayName("整系列查询排除同系列单章和元数据任务，保留暂停与取消的整系列任务")
    void seriesLookupMatchesWholeSeriesScope() {
        var pending = new ArrayList<Runnable>();
        var jobs = new TranslationJobs(store, translations, documents, pending::add, new QueueTaskTracker("translation"));
        var single = jobs.submit(new TranslationJobs.Submission(UUID.randomUUID(), request().request(), 9L, false, false, null));
        jobs.submit(new TranslationJobs.Submission(UUID.randomUUID(), new NovelTranslationOperations.Request(
                0, "zh-CN", 0, true, null, null, false, true, false, "libretranslate"), 9L, false, false, null, true));
        assertThat(jobs.find(0, 9L)).isEmpty();
        var series = jobs.submit(new TranslationJobs.Submission(UUID.randomUUID(), request().request(), 9L, true, false, null));
        assertThat(series.allChapters()).isTrue();
        assertThat(single.allChapters()).isFalse();
        assertThat(jobs.find(0, 9L)).extracting(TranslationJobs.View::id).containsExactly(series.id());
        jobs.stop(series.id(), false);
        assertThat(jobs.find(0, 9L)).extracting(TranslationJobs.View::state).containsExactly("PAUSED");
        jobs.stop(series.id(), true);
        assertThat(jobs.find(0, 9L)).extracting(TranslationJobs.View::state).containsExactly("CANCELLED");
        assertThat(jobs.find(7, null)).extracting(TranslationJobs.View::id).contains(single.id());
    }

    @Test @DisplayName("默认日志可关联接纳、开始和终态，不泄露原文或译文")
    void logsJobOutcomeWithoutContent(org.springframework.boot.test.system.CapturedOutput output) {
        config.getLibretranslate().setRequestChars(100);
        when(documents.source(Kind.NOVEL, 7)).thenReturn(
                new TranslationDocuments.Content(7, null, "fixture-private-original", "source", null));
        response("fixture-private-translation");
        var result = jobs().submit(request());
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(output.getAll()).contains("Translation job accepted:", "Translation job started:",
                "Translation job stopped:", result.id().toString(), result.runId(), "state=COMPLETED", "durationMs=")
                .doesNotContain("fixture-private-original", "fixture-private-translation");
        server.verify();
    }

    @Test @DisplayName("退出状态写入失败后可修正孤立运行状态，并复用成功检查点")
    void recoversAfterTerminalPersistenceFailure() {
        var storageUnavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        store = spy(store);
        doAnswer(call -> {
            TranslationJobs.Job job = call.getArgument(0);
            if ("PAUSED".equals(job.state) && storageUnavailable.get())
                throw new IllegalStateException("translation.job-storage");
            return call.callRealMethod();
        }).when(store).save(any());
        response("甲"); fail();
        var jobs = jobs();
        var submitted = jobs.submit(request());
        assertThatThrownBy(() -> jobs.get(submitted.id())).hasMessage("translation.job-storage");
        storageUnavailable.set(false);
        assertThat(jobs.find(7, null).get(0).state()).isEqualTo("PAUSED");
        server.verify(); server.reset();
        response("乙"); response("丙");
        assertThat(jobs.resume(submitted.id()).state()).isEqualTo("COMPLETED");
        assertThat(publishedBody).isEqualTo("甲\n乙\n丙");
        server.verify();
    }

    @Test @DisplayName("中途失败保留旧译文，重建服务后只请求尚未成功的分段")
    void resumesAfterRestartWithoutRepeatingPaidBatch() throws Exception {
        response("甲"); fail();
        var request = request();
        var first = jobs().submit(request);
        assertThat(first.state()).isEqualTo("PAUSED");
        assertThat(first.savedSteps()).isEqualTo(1);
        assertThat(publishedBody).isNull();
        server.verify(); server.reset();
        response("乙"); response("丙");
        store = new TranslationJobStore(database(directory.resolve("jobs.db")), json);
        var result = jobs().resume(request.requestId());
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(publishedBody).isEqualTo("甲\n乙\n丙");
        assertThat(result.runId()).isNotEqualTo(first.runId());
        server.verify();
    }

    @Test @DisplayName("恢复前拒绝原文改变，不发送后续请求或覆盖旧结果")
    void refusesChangedSource() {
        response("甲"); fail();
        var request = request(); var jobs = jobs();
        jobs.submit(request); server.verify(); server.reset();
        when(documents.source(Kind.NOVEL, 7)).thenReturn(new TranslationDocuments.Content(7, null, "changed", "source", "description"));
        assertThat(jobs.resume(request.requestId()).reason()).isEqualTo("translation.source-changed");
        assertThat(publishedBody).isNull(); server.verify();
    }

    @Test @DisplayName("恢复时拒绝引擎参数改变，重复提交返回同一身份且异参拒绝")
    void configurationAndIdempotency() {
        fail();
        var request = request(); var jobs = jobs();
        var first = jobs.submit(request);
        assertThat(jobs.submit(request).id()).isEqualTo(first.id());
        var changed = new TranslationJobs.Submission(request.requestId(), request.request(), null, false, true, null);
        assertThatThrownBy(() -> jobs.submit(changed)).hasMessage("translation.job-conflict");
        config.getLibretranslate().setRequestChars(4);
        assertThatThrownBy(() -> jobs.resume(first.id())).hasMessage("translation.configuration-changed");
        server.verify();
    }

    @Test @DisplayName("发布确认丢失后重放已保存响应，发布身份保持不变")
    void publicationAcknowledgementLoss() {
        response("甲"); response("乙"); response("丙");
        doAnswer(call -> {
            receipts.add(((NovelTranslationDocuments.Publication) call.getArgument(6)).id());
            throw new IllegalStateException("transport-lost");
        }).when(documents).publish(any(), anyLong(), anyString(), any(), any(), any(), any());
        var request = request(); var jobs = jobs();
        assertThat(jobs.submit(request).state()).isEqualTo("PAUSED");
        server.verify(); server.reset();
        doAnswer(call -> {
            assertThat(receipts).contains(((NovelTranslationDocuments.Publication) call.getArgument(6)).id());
            return null;
        }).when(documents).publish(any(), anyLong(), anyString(), any(), any(), any(), any());
        assertThat(jobs.resume(request.requestId()).state()).isEqualTo("COMPLETED");
        server.verify();
    }

    @Test @DisplayName("排队取消先结算真实 drain，重启恢复只暂停且不访问服务")
    void queuedCancellationAndCrashRecovery() {
        var queued = new ArrayList<Runnable>();
        var tracker = new QueueTaskTracker("translation");
        var jobs = new TranslationJobs(store, translations, documents, queued::add, tracker);
        var request = request(); var job = jobs.submit(request);
        assertThat(job.state()).isEqualTo("QUEUED");
        assertThat(jobs.stop(job.id(), true).state()).isEqualTo("CANCELLED");
        assertThat(tracker.activeTaskCount()).isZero();
        queued.get(0).run();
        var next = jobs.submit(request());
        var recovered = jobs().get(next.id());
        assertThat(recovered.state()).isEqualTo("PAUSED");
        assertThat(recovered.reason()).isEqualTo("translation.interrupted");
        assertThat(publishedBody).isNull();
        tracker.quiesce();
    }

    @Test @DisplayName("同系列按提交顺序执行，取消中间任务不会让后序越过前序")
    void serialQueueSurvivesCancellationInMiddle() {
        var queued = new ArrayList<Runnable>();
        var tracker = new QueueTaskTracker("translation");
        var jobs = new TranslationJobs(store, translations, documents, queued::add, tracker);
        var first = jobs.submit(request());
        var middle = jobs.submit(request());
        var last = jobs.submit(request());
        assertThat(queued).hasSize(1);
        assertThat(jobs.stop(middle.id(), true).state()).isEqualTo("CANCELLED");
        assertThat(queued).hasSize(1);
        response("甲"); response("乙"); response("丙");
        queued.get(0).run();
        assertThat(jobs.get(first.id()).state()).isEqualTo("COMPLETED");
        assertThat(queued).hasSize(2);
        assertThat(jobs.get(last.id()).state()).isEqualTo("QUEUED");
        server.verify();
        tracker.quiesce();
        assertThat(tracker.activeTaskCount()).isZero();
    }

    @Test @DisplayName("AI 恢复重放已保存术语上下文，模型截断不得发布或作为成功检查点")
    void aiRestoresGlossaryAndRejectsTruncation() throws Exception {
        var ai = mock(AiChatClient.class);
        when(ai.isConfigured()).thenReturn(true);
        when(ai.bind()).thenReturn(Optional.of(ai));
        when(ai.configurationFingerprint()).thenReturn(Optional.of("fixture-model"));
        config.setEngine("ai"); config.setAiSegmentSize(3);
        when(documents.source(Kind.NOVEL, 7)).thenReturn(new TranslationDocuments.Content(7, null, "abc\n\ndef\n\nghi", "source", "description"));
        when(documents.glossaryEntries(5, 1000)).thenReturn(List.of(new NovelTranslationDocuments.Term("Bob", "zh-CN", "鲍勃")));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.chatBound(anyString(), anyString(), anyList(), any())).thenAnswer(call -> {
            String type = call.getArgument(1);
            String content;
            String finish = "stop";
            if (type.equals(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE))
                content = "{\"code\":\"zh-CN\",\"valid\":true}";
            else if (type.equals(top.sywyar.pixivdownload.translation.ai.SourceLanguageProbeRequest.CALL_TYPE))
                content = "{\"same\":false}";
            else {
                int n = calls.incrementAndGet();
                if (n >= 2) assertThat(json.writeValueAsString(call.getArgument(2))).contains("Alice", "Bob").doesNotContain("changed-term");
                if (n == 2) finish = "length";
                content = "{\"status\":\"ok\",\"lang\":\"zh-CN\",\"text\":\"译文\",\"glossary\":[{\"source\":\"Alice\",\"target\":\"爱丽丝\"}]}";
            }
            return new top.sywyar.pixivdownload.ai.model.AiChatResult(content, finish, null, null, null);
        });
        translations = new NovelTranslationService(config, ai, documents,
                new MachineTranslationClient(config, http, http, json), mock(MessageResolver.class), () -> true);
        var request = new TranslationJobs.Submission(UUID.randomUUID(), new NovelTranslationOperations.Request(7, "zh-CN", 3,
                true, null, 5L, true, false, false, "ai"), null, false, false, null);
        var jobs = jobs();
        assertThat(jobs.submit(request).reason()).isEqualTo("translation.incomplete-response");
        assertThat(publishedBody).isNull();
        assertThat(calls).hasValue(2);
        when(documents.glossaryEntries(5, 1000)).thenReturn(List.of(new NovelTranslationDocuments.Term("changed-term", "zh-CN", "新词")));
        assertThat(jobs().resume(request.requestId()).state()).isEqualTo("COMPLETED");
        assertThat(calls).hasValue(4);
        verify(ai, times(1)).chatBound(anyString(), eq(top.sywyar.pixivdownload.translation.ai.LangProbeRequest.CALL_TYPE), anyList(), any());
        assertThat(publishedBody).isEqualTo("译文\n译文\n译文");
    }

    @Test @DisplayName("服务撤回停止准入并等待运行任务退出，已返回响应保存但不发布")
    void runningCancellationKeepsCheckpointAndDrains() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.expect(requestTo("http://localhost:5000/translate")).andRespond(request -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return withSuccess("{\"translatedText\":\"甲\"}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var tracker = new QueueTaskTracker("translation");
        var jobs = new TranslationJobs(store, translations, documents, executor::execute, tracker);
        try {
            var request = request(); jobs.submit(request);
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var drain = tracker.prepareQuiesce();
            tracker.cancelQuiescedTasks();
            assertThat(tracker.activeTaskCount()).isEqualTo(1);
            assertThatThrownBy(() -> jobs.submit(request())).hasMessage("translation.unavailable");
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(tracker.activeTaskCount()).isZero();
            assertThat(jobs.get(request.requestId()).state()).isEqualTo("PAUSED");
            assertThat(jobs.get(request.requestId()).savedSteps()).isEqualTo(1);
            assertThat(publishedBody).isNull();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test @DisplayName("系列元数据使用持久任务，成功后只合订选定引擎的变体")
    void seriesRunsOnBackendAndMergesOnlyChosenVariant() {
        when(documents.seriesNovelIds(9, 0, 100)).thenReturn(List.of(7L));
        when(documents.seriesNovelIds(9, 1, 100)).thenReturn(List.of());
        when(documents.source(Kind.SERIES, 9)).thenReturn(new TranslationDocuments.Content(9, 9L, null, "abc", "def"));
        response("甲"); response("乙"); response("丙"); response("标题"); response("简介");
        var r = new NovelTranslationOperations.Request(0, "zh-CN", 0, true, null, null, true, true, true, "libretranslate");
        when(documents.source(Kind.NOVEL, 7)).thenReturn(new TranslationDocuments.Content(7, 9L, "abc", "def", "ghi"));
        var job = jobs().submit(new TranslationJobs.Submission(UUID.randomUUID(), r, 9L, true, true, "epub"));
        assertThat(job.state()).isEqualTo("COMPLETED");
        verify(documents).mergeSeries(9, "epub", "zh-CN");
        verify(documents, never()).mergeSeries(9, "epub");
        server.verify();
    }
}
