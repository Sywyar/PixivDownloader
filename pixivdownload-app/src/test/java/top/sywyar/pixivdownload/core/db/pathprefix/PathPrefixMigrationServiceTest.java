package top.sywyar.pixivdownload.core.db.pathprefix;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionOperations;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMigrationService.PathPrefixMigrationResult;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMigrationService.PathPrefixUpdate;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathPrefixMigrationService 批量改写测试")
class PathPrefixMigrationServiceTest {

    private static final PathPrefixColumns PATH_COLUMNS =
            DatabaseSchemaRegistry.forBuiltInPlugins().pathPrefixColumns();

    private SingleConnectionDataSource dataSource;
    private PathPrefixMapper mapper;
    private PathPrefixCodec codec;
    private PathPrefixMigrationService service;

    private long idA;
    private long idB;
    private long idC;
    private Path dirA;
    private Path dirB;
    private Path dirC;
    private Path dirD;
    private Path dirE;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        mapper = new PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));

        // 默认相对 root → 符号根 {0} 启用
        codec = new PathPrefixCodec(mapper, new DownloadConfig(), TestI18nBeans.appMessages());
        codec.init();

        // 符号根改写需要触达全部路径前缀列：补建最小化的内容表
        top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializePathPrefixes(dataSource);
        createPathColumnTables();

        // 真实存在的目录：validatePath 要求目标必须是已存在的绝对目录
        dirA = Files.createDirectory(tempDir.resolve("a"));
        dirB = Files.createDirectory(tempDir.resolve("b"));
        dirC = Files.createDirectory(tempDir.resolve("c"));
        dirD = Files.createDirectory(tempDir.resolve("d"));
        dirE = Files.createDirectory(tempDir.resolve("e"));

        idA = codec.getOrCreatePrefixId(dirA.toString());
        idB = codec.getOrCreatePrefixId(dirB.toString());
        idC = codec.getOrCreatePrefixId(dirC.toString());

        service = new PathPrefixMigrationService(codec, mapper, PATH_COLUMNS, new DownloadConfig(),
                TestI18nBeans.appMessages(), new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
                top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource), directories(codec));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/api/tools/path-prefixes", "/api/gui/path-prefixes"})
    void registrationReportsCommittedChangesAndRollsBackMixedWrites(String route) throws Exception {
        var mvc = http();
        boolean tool = route.contains("/tools/");
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Host", "localhost").contentType("application/json");
        var json = tools.jackson.databind.json.JsonMapper.shared();
        var body = json.createObjectNode();
        var paths = body.putArray("registerPaths").add(dirD.toString()).add(123);
        var response = mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(tool ? 400 : 200);
        var error = json.readTree(response.getContentAsByteArray());
        assertThat(error.at(tool ? "/field" : "/errors/0/field").asString()).isEqualTo("/registerPaths/1");
        assertThat(mapper.findIdByPath(dirD.toString())).isNull();
        assertThat(mapper.findIdByPath("123")).isNull();

        paths.removeAll().add(dirD.toString()).add("relative-directory");
        response = mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(tool ? 400 : 200);
        error = json.readTree(response.getContentAsByteArray());
        assertThat(error.at("/errors/0/field").asString()).isEqualTo("/registerPaths/1");
        assertThat(error.at("/errors/0/reason").asString()).isEqualTo("not-absolute");
        assertThat(mapper.findIdByPath("relative-directory")).isNull();
        assertThat(mapper.findIdByPath(dirD.toString())).isNull();

        paths.removeAll().add(dirE.toString()).add(dirB.toString());
        body.putArray("updates").addObject().put("id", idA).put("path", dirD.toString());
        execute("CREATE TRIGGER reject_prefix BEFORE INSERT ON path_prefixes BEGIN SELECT RAISE(ABORT, 'fixture registration failed'); END");
        response = mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(tool ? 500 : 200);
        var failed = json.readTree(response.getContentAsByteArray());
        assertThat(failed.required("success").asBoolean()).isFalse();
        assertThat(failed.required("applied").asInt()).isZero();
        assertThat(failed.at("/errors/0/reason").asString()).isEqualTo("write-failed");
        assertThat(stored(idA)).isEqualTo(strip(dirA));
        assertThat(mapper.findIdByPath(dirE.toString())).isNull();
        execute("DROP TRIGGER reject_prefix");

        response = mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var result = json.readValue(response.getContentAsByteArray(), PathPrefixMigrationResult.class);
        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isEqualTo(2);
        assertThat(result.registered()).isOne();
        assertThat(result.alreadyRegistered()).isOne();
        assertThat(stored(idA)).isEqualTo(strip(dirD));
        var prefixId = mapper.findIdByPath(dirE.toString());
        assertThat(prefixId).isNotNull();
        body.remove("updates");
        paths.removeAll().add(dirE.toString());
        result = json.readValue(mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn().getResponse().getContentAsByteArray(),
                PathPrefixMigrationResult.class);
        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isZero();
        assertThat(result.registered()).isZero();
        assertThat(result.alreadyRegistered()).isOne();
        assertThat(mapper.findIdByPath(dirE.toString())).isEqualTo(prefixId);
    }

    @Test
    void codecRejectsRelativePrefixesAndPreservesAbsoluteLibraryPathsFromOtherPlatforms() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> codec.forceCreatePrefixId("relative"));
        assertThat(mapper.findIdByPath("relative")).isNull();
        for (String path : List.of("/old/library", "D:/old/library", "\\\\server\\share\\library")) {
            assertThat(codec.forceCreatePrefixId(path)).isPositive();
            assertThat(mapper.findIdByPath(path)).isNotNull();
        }
    }

    private top.sywyar.pixivdownload.core.asset.DownloadDirectories directories(PathPrefixCodec codec) {
        return new top.sywyar.pixivdownload.core.asset.DownloadDirectories(
                top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource), codec, null);
    }

    private void createPathColumnTables() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE artwork_image_hashes(artwork_id INTEGER, page INTEGER)");
            for (var table : PATH_COLUMNS.all()) {
                st.execute("CREATE TABLE " + table.table() + " (" + table.idColumn() + (table.idColumn().equals("reference_id") || table.idColumn().equals("entry_id")
                        ? " TEXT PRIMARY KEY, " : " INTEGER PRIMARY KEY, ")
                        + String.join(", ", table.columns().stream().map(column -> column + " TEXT").toList()) + ")");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private org.springframework.test.web.servlet.MockMvc http() {
        var messages = TestI18nBeans.appMessages();
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new PathPrefixToolsController(service, new top.sywyar.pixivdownload.gui.ToolApiRequests(messages,
                        org.mockito.Mockito.mock(top.sywyar.pixivdownload.i18n.WebI18nService.class)), messages),
                new top.sywyar.pixivdownload.gui.controller.PathPrefixController(service)).build();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    @org.junit.jupiter.params.provider.ValueSource(strings = {"register", "update", "pin"})
    void preservesTrailingSpaceInDirectorySelection(String operation) throws Exception {
        Path neighbour = Files.createDirectory(dirD.resolve("images"));
        Path selected = Files.createDirectory(dirD.resolve("images "));
        long neighbourId = codec.getOrCreatePrefixId(neighbour.toString());
        var json = tools.jackson.databind.json.JsonMapper.shared();
        var body = json.createObjectNode();
        String route = "/api/tools/path-prefixes";
        switch (operation) {
            case "register" -> body.putArray("registerPaths").add(selected.toString());
            case "update" -> body.putArray("updates").addObject().put("id", idA).put("path", selected.toString());
            case "pin" -> {
                execute("INSERT INTO artworks(artwork_id, folder) VALUES(1, '{0}/work')");
                route += "/pin";
                body.put("path", selected.toString());
            }
        }
        var response = http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json.readTree(response.getContentAsByteArray()).required("applied").asInt()).isOne();
        Long id = mapper.findIdByPath(selected.toString());
        assertThat(id).isNotNull().isNotEqualTo(neighbourId);
        if (operation.equals("update")) assertThat(id).isEqualTo(idA);
        if (operation.equals("pin")) assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id=1"))
                .isEqualTo("{" + id + "}/work");
        String stored = mapper.findPathById(id);
        assertThat(stored).isEqualTo(selected.toString());
        assertThat(Files.isSameFile(Path.of(stored), selected)).isTrue();
        assertThat(Files.isSameFile(Path.of(stored), neighbour)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void equivalentRegistrationsShareTheIdUsedByArtworkEncoding() throws Exception {
        String forward = dirD.toString().replace('\\', '/');
        assertThat(Files.isSameFile(dirD, Path.of(forward))).isTrue();
        var mvc = http();
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tools/path-prefixes")
                .contentType("application/json");
        var json = tools.jackson.databind.json.JsonMapper.shared();
        var body = json.createObjectNode();
        String dotted = dirD.resolve(".").toString();
        assertThat(Files.isSameFile(dirD, Path.of(dotted))).isTrue();
        var paths = body.putArray("registerPaths").add(forward).add(dirD.toString()).add(dotted);
        var response = mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var result = json.readValue(response.getContentAsByteArray(), PathPrefixMigrationResult.class);
        assertThat(result.registered()).isOne();
        assertThat(result.alreadyRegistered()).isEqualTo(2);
        paths.removeAll().add(dotted.toUpperCase(java.util.Locale.ROOT));
        result = json.readValue(mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn()
                .getResponse().getContentAsByteArray(), PathPrefixMigrationResult.class);
        assertThat(result.registered()).isZero();
        assertThat(result.alreadyRegistered()).isOne();
        var listed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tools/path-prefixes"))
                .andReturn().getResponse();
        var ids = json.readTree(listed.getContentAsByteArray()).required("prefixes").valueStream()
                .filter(p -> Path.of(p.required("path").asString()).equals(dirD)).map(p -> p.required("id").asLong()).toList();
        assertThat(ids).hasSize(1);
        long id = ids.getFirst();
        String token = "{" + id + "}/work";
        assertThat(codec.encode(forward + "/work")).isEqualTo(token);
        assertThat(codec.encode(dirD.resolve("work").toString())).isEqualTo(token);
        execute("INSERT INTO artworks(artwork_id,folder) VALUES(1,'" + token + "')");
        String second = codec.encode(dotted + "\\.\\Cover");
        assertThat(second).isEqualTo("{" + id + "}/Cover");
        execute("INSERT INTO artworks(artwork_id,folder) VALUES(2,'" + second + "')");
        assertThat(service.apply(List.of(new PathPrefixUpdate(idC, dotted)), List.of()).success()).isFalse();
        body.remove("registerPaths");
        body.putArray("updates").addObject().put("id", id).put("path", dirE.toString());
        result = json.readValue(mvc.perform(post.content(json.writeValueAsBytes(body))).andReturn()
                .getResponse().getContentAsByteArray(), PathPrefixMigrationResult.class);
        assertThat(result.applied()).isOne();
        assertThat(Path.of(codec.resolve(queryString("SELECT folder FROM artworks WHERE artwork_id=1")))).isEqualTo(dirE.resolve("work"));
        assertThat(Path.of(codec.resolve(queryString("SELECT folder FROM artworks WHERE artwork_id=2")))).isEqualTo(dirE.resolve("Cover"));
    }

    @Test
    void posixLibraryPrefixesKeepCaseAndLiteralBackslashes() {
        String upper = "/Library/Art";
        String lower = "/Library/art";
        String trailingBackslash = "/Library/art\\";
        long a = codec.forceCreatePrefixId(upper);
        long b = codec.forceCreatePrefixId(lower);
        long c = codec.forceCreatePrefixId(trailingBackslash);
        assertThat(List.of(a, b, c)).doesNotHaveDuplicates();
        assertThat(codec.encode(upper + "/cover")).isEqualTo("{" + a + "}/cover");
        assertThat(codec.encode(lower + "/cover")).isEqualTo("{" + b + "}/cover");
        assertThat(codec.encode(trailingBackslash + "/cover")).isEqualTo("{" + c + "}/cover");
        assertThat(codec.resolve("{" + c + "}/cover")).isEqualTo(trailingBackslash + "/cover");
        assertThat(codec.forceCreatePrefixId("/Library/./Art/.")).isEqualTo(a);
        assertThat(codec.encode("/Library/./Art/./.cover")).isEqualTo("{" + a + "}/.cover");
        assertThat(codec.encode("/Library/other/../Art/cover")).isEqualTo("/Library/other/../Art/cover");
        assertThat(codec.forceCreatePrefixId(lower + "\\.")).isNotEqualTo(b);
        assertThat(PathPrefixCodec.comparisonKey("\\\\server\\share\\.\\Art\\.")).isEqualTo(PathPrefixCodec.comparisonKey("//server/share/Art"));
    }

    @Test
    void legacyPrefixIdsRemainIndividuallyResolvableAndNewReferencesChooseTheLowestId() {
        String first = "D:/Legacy/Library";
        String alias = "d:\\legacy\\LIBRARY";
        long firstId = codec.forceCreatePrefixId(first);
        mapper.insertIfAbsent(alias);
        long aliasId = mapper.findIdByPath(alias);
        codec.reload();
        assertThat(codec.getOrCreatePrefixId(alias)).isEqualTo(firstId);
        assertThat(codec.encode(alias + "\\work")).isEqualTo("{" + firstId + "}/work");
        assertThat(service.apply(List.of(new PathPrefixUpdate(firstId, dirD.toString())), List.of()).success()).isTrue();
        assertThat(codec.resolve("{" + firstId + "}/work")).isEqualTo(dirD + "/work");
        assertThat(codec.resolve("{" + aliasId + "}/old")).isEqualTo(alias + "/old");
        assertThat(codec.getOrCreatePrefixId(first)).isEqualTo(aliasId);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/api/tools/path-prefixes/pin", "/api/gui/path-prefixes/pin"})
    void pinReportsStorageFailureBeforeTheSameInputSucceeds(String route) throws Exception {
        execute("INSERT INTO artworks(artwork_id, folder) VALUES(1, '{0}/100')");
        var json = tools.jackson.databind.json.JsonMapper.shared();
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Host", "localhost").contentType("application/json")
                .content(json.writeValueAsBytes(java.util.Map.of("path", dirD.toString())));
        var mvc = http();
        execute("CREATE TRIGGER reject_pin BEFORE INSERT ON path_prefixes BEGIN SELECT RAISE(ABORT,'fixture write failure'); END");
        var response = mvc.perform(post).andReturn().getResponse();
        boolean tool = route.contains("/tools/");
        assertThat(response.getStatus()).isEqualTo(tool ? 500 : 200);
        var failure = json.readTree(response.getContentAsByteArray());
        if (tool) assertThat(failure.required("code").asString()).isEqualTo("PATH_PREFIX_WRITE_FAILED");
        assertThat(failure.required("success").asBoolean()).isFalse();
        assertThat(failure.required("applied").asInt()).isZero();
        assertThat(failure.at("/errors/0/reason").asString()).isEqualTo("write-failed");
        assertThat(codec.isSymbolicRootActive()).isTrue();
        assertThat(mapper.findIdByPath(dirD.toString())).isNull();
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id=1")).isEqualTo("{0}/100");
        execute("DROP TRIGGER reject_pin");
        // The insert succeeds, then a real duplicate-key failure interrupts reference rewriting.
        execute("CREATE TRIGGER collide_pin AFTER UPDATE OF folder ON artworks BEGIN INSERT INTO artworks(artwork_id) VALUES(1); END");
        response = mvc.perform(post).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(tool ? 400 : 200);
        var conflict = json.readTree(response.getContentAsByteArray());
        if (tool) assertThat(conflict.required("code").asString()).isEqualTo("PATH_PREFIX_REJECTED");
        assertThat(conflict.at("/errors/0/reason").asString()).isEqualTo("conflict");
        assertThat(mapper.findIdByPath(dirD.toString())).isNull();
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id=1")).isEqualTo("{0}/100");
        assertThat(codec.isSymbolicRootActive()).isTrue();
        execute("DROP TRIGGER collide_pin");
        response = mvc.perform(post).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json.readTree(response.getContentAsByteArray()).required("success").asBoolean()).isTrue();
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id=1"))
                .isEqualTo("{" + mapper.findIdByPath(dirD.toString()) + "}/100");
        assertThat(codec.isSymbolicRootActive()).isFalse();
    }

    @Test
    @DisplayName("链式重命名（A→D, B→C, C→E）应成功而不触发 UNIQUE 约束")
    void shouldApplyChainedRenameWithoutUniqueViolation() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(idA, dirD.toString()),
                new PathPrefixUpdate(idB, dirC.toString()),
                new PathPrefixUpdate(idC, dirE.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();
        assertThat(stored(idA)).isEqualTo(strip(dirD));
        assertThat(stored(idB)).isEqualTo(strip(dirC));
        assertThat(stored(idC)).isEqualTo(strip(dirE));
    }

    @Test
    @DisplayName("两行路径互换（A↔B）应成功")
    void shouldSwapTwoPrefixesWithoutUniqueViolation() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(idA, dirB.toString()),
                new PathPrefixUpdate(idB, dirA.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isEqualTo(2);
        assertThat(stored(idA)).isEqualTo(strip(dirB));
        assertThat(stored(idB)).isEqualTo(strip(dirA));
        assertThat(stored(idC)).isEqualTo(strip(dirC));
    }

    @Test
    @DisplayName("目标与另一行当前路径重复应被拒绝且一行不改")
    void shouldRejectDuplicateTargetAndApplyNothing() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(idA, dirB.toString())), List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.applied()).isZero();
        assertThat(result.errors()).extracting(PathPrefixMigrationService.PrefixError::reason)
                .containsExactly("duplicate");
        // 未写入任何记录
        assertThat(stored(idA)).isEqualTo(strip(dirA));
        assertThat(stored(idB)).isEqualTo(strip(dirB));
    }

    @Test
    @DisplayName("符号根启用时 list 应置顶返回 id 0 的虚拟行")
    void shouldListSymbolicRootFirst() {
        var views = service.list();

        assertThat(views).isNotEmpty();
        assertThat(views.get(0).id()).isZero();
        assertThat(views.get(0).symbolic()).isTrue();
        assertThat(views.get(0).downloadRoot()).isTrue();
        assertThat(views.get(0).path()).isEqualTo(codec.getSymbolicRootPath());
        assertThat(views.stream().skip(1)).allMatch(v -> !v.symbolic());
    }

    @Test
    @DisplayName("改写符号根应把全部路径前缀列中的 {0} 引用替换为新前缀 {N}")
    void shouldRewriteSymbolicRootReferencesToNewPrefix() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', '{0}')");
        execute("INSERT INTO novels(novel_id, folder) VALUES(2, '{0}/novel-2')");
        execute("INSERT INTO collections(id, download_root) VALUES(3, '{0}/col')");

        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(0L, dirD.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isEqualTo(1);
        Long newId = mapper.findIdByPath(strip(dirD));
        assertThat(newId).isNotNull();
        String token = "{" + newId + "}";
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo(token + "/100");
        assertThat(queryString("SELECT move_folder FROM artworks WHERE artwork_id = 1")).isEqualTo(token);
        assertThat(queryString("SELECT folder FROM novels WHERE novel_id = 2")).isEqualTo(token + "/novel-2");
        assertThat(queryString("SELECT download_root FROM collections WHERE id = 3")).isEqualTo(token + "/col");
    }

    @Test
    @DisplayName("符号根目标与现有前缀行同路径时应合并复用该行而不报错")
    void shouldMergeSymbolicRootIntoExistingPrefixRow() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', NULL)");

        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(0L, dirA.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1"))
                .isEqualTo("{" + idA + "}/100");
    }

    @Test
    @DisplayName("符号根新路径与当前解析结果相同应视为未改动")
    void shouldIgnoreSymbolicRootUpdateToSamePath() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(0L, codec.getSymbolicRootPath())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isZero();
    }

    @Test
    @DisplayName("符号根未启用（绝对 root）时对 id 0 的改写应报 unknown-id")
    void shouldRejectSymbolicRootUpdateWhenInactive(@TempDir Path absRoot) {
        DownloadConfig absConfig = new DownloadConfig();
        absConfig.setRootFolder(absRoot.toString() + "/{artwork_id}");
        PathPrefixCodec absCodec = new PathPrefixCodec(mapper, absConfig, TestI18nBeans.appMessages());
        absCodec.init();
        PathPrefixMigrationService absService = new PathPrefixMigrationService(absCodec, mapper, PATH_COLUMNS, absConfig,
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource), directories(absCodec));

        PathPrefixMigrationResult result = absService.apply(List.of(
                new PathPrefixUpdate(0L, dirD.toString())), List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).extracting(PathPrefixMigrationService.PrefixError::reason)
                .containsExactly("unknown-id");
    }

    @Test
    @DisplayName("pin 符号根到任意已存在目录应改写全部 {0} 引用")
    void shouldPinSymbolicRootToGivenPath() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', '{0}')");
        execute("INSERT INTO novels(novel_id, folder) VALUES(2, '{0}/novel-2')");

        PathPrefixMigrationResult result = service.pinSymbolicRoot(dirD.toString());

        assertThat(result.success()).isTrue();
        Long newId = mapper.findIdByPath(strip(dirD));
        assertThat(newId).isNotNull();
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1"))
                .isEqualTo("{" + newId + "}/100");
        assertThat(queryString("SELECT folder FROM novels WHERE novel_id = 2"))
                .isEqualTo("{" + newId + "}/novel-2");
    }

    @Test
    @DisplayName("pin 到符号根当前解析路径应强制建行（冻结旧记录场景）")
    void shouldPinSymbolicRootToItsOwnResolutionByForceCreatingRow() {
        // root 设为已存在的相对目录 target → 符号根启用且解析路径真实存在，满足 validatePath
        DownloadConfig relConfig = new DownloadConfig();
        relConfig.setRootFolder("target" + "/{artwork_id}");
        PathPrefixCodec relCodec = new PathPrefixCodec(mapper, relConfig, TestI18nBeans.appMessages());
        relCodec.init();
        PathPrefixMigrationService relService = new PathPrefixMigrationService(relCodec, mapper, PATH_COLUMNS, relConfig,
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource), directories(relCodec));
        String rootPath = relCodec.getSymbolicRootPath();
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(9, '{0}/900', NULL)");

        PathPrefixMigrationResult result = relService.pinSymbolicRoot(rootPath);

        assertThat(result.success()).isTrue();
        Long newId = mapper.findIdByPath(rootPath);
        assertThat(newId).isNotNull(); // getOrCreatePrefixId 守卫会返回 0，pin 必须强制建行
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 9"))
                .isEqualTo("{" + newId + "}/900");
    }

    @Test
    @DisplayName("pin 冻结符号根后应停用 {0} 编码，新记录改用固定后的 {N}（未重启窗口防误写）")
    void shouldStopEncodingAsSymbolicRootAfterPin() {
        // root 设为已存在的相对目录 target → 符号根启用且解析路径真实存在，满足 validatePath
        DownloadConfig relConfig = new DownloadConfig();
        relConfig.setRootFolder("target" + "/{artwork_id}");
        PathPrefixCodec relCodec = new PathPrefixCodec(mapper, relConfig, TestI18nBeans.appMessages());
        relCodec.init();
        PathPrefixMigrationService relService = new PathPrefixMigrationService(relCodec, mapper, PATH_COLUMNS, relConfig,
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource), directories(relCodec));
        String rootPath = relCodec.getSymbolicRootPath();
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(9, '{0}/900', NULL)");
        assertThat(relCodec.isSymbolicRootActive()).isTrue();
        assertThat(relCodec.encode(rootPath + "/new")).isEqualTo("{0}/new");

        PathPrefixMigrationResult result = relService.pinSymbolicRoot(rootPath);

        assertThat(result.success()).isTrue();
        // pin 后符号根停用，list / 状态不再把 {0} 当作可编码候选
        assertThat(relCodec.isSymbolicRootActive()).isFalse();
        Long newId = mapper.findIdByPath(rootPath);
        assertThat(newId).isNotNull();
        // 旧记录已固定为 {N}
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 9"))
                .isEqualTo("{" + newId + "}/900");
        // 关键：尚未重启时新下载（仍落在旧 root）应编码成固定后的 {N}，而不是会随新配置漂移的 {0}
        assertThat(relCodec.encode(rootPath + "/new")).isEqualTo("{" + newId + "}/new");
    }

    @Test
    @DisplayName("hasSymbolicRootRows 应识别反斜杠分隔的 {0}\\... 引用")
    void shouldDetectBackslashSeparatedSymbolicRows() {
        assertThat(service.symbolicRootStatus().referenced()).isFalse();
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(7, '{0}\\deep', NULL)");
        assertThat(service.symbolicRootStatus().referenced()).isTrue();
    }

    @Test
    @DisplayName("pin 到不存在的目录应报 not-exist 且不改写任何记录")
    void shouldRejectPinToMissingDirectory() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', NULL)");

        PathPrefixMigrationResult result = service.pinSymbolicRoot(dirD.resolve("missing").toString());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).extracting(PathPrefixMigrationService.PrefixError::reason)
                .containsExactly("not-exist");
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}/100");
    }

    private void execute(String sql) {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String queryString(String sql) {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String stored(long id) {
        return mapper.findPathById(id);
    }

    private static String strip(Path dir) {
        return PathPrefixCodec.stripTrailingSeparators(dir.toString());
    }
}
