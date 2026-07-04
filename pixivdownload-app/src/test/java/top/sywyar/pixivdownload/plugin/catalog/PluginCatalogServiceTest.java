package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogMarketMeta;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link PluginCatalogService} 单测：未启用 → 空 / 拒绝，UTF-8 + Jackson 解析（成功 / 空 / 坏 JSON / 忽略未知字段），以及
 * 经 loopback 桩拉取清单（成功 / 不可用）。下载地址只来自注入的属性配置，不来自请求参数。
 */
@DisplayName("PluginCatalogService 受信目录读取")
class PluginCatalogServiceTest {

    private final PluginCatalogHttpClient relaxed = new PluginCatalogHttpClient(false, true, 2000, 2000);
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("未启用（enabled=false）：isEnabled=false，load() 抛 CATALOG_DISABLED")
    void disabledByFlag() {
        PluginCatalogService service = service(false, "https://example.com/c.json");
        assertThat(service.isEnabled()).isFalse();
        assertCode(service, PluginCatalogErrorCode.CATALOG_DISABLED);
    }

    @Test
    @DisplayName("主开关开但无启用仓库（官方仓库禁用 + 未配置 manifest-url）：isEnabled=false，load() 抛 CATALOG_DISABLED")
    void enabledButNoEnabledRepository() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setOfficialRepositoryEnabled(false);
        PluginCatalogService service = new PluginCatalogService(props, relaxed);
        assertThat(service.isEnabled()).isFalse();
        assertCode(service, PluginCatalogErrorCode.CATALOG_DISABLED);
    }

    @Test
    @DisplayName("主开关开 + 官方仓库默认启用 + 未配置自定义仓库：isEnabled=true（默认仓库为内嵌官方，不实际联网）")
    void enabledWithOfficialDefault() {
        PluginCatalogService service = service(true, "");
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("解析合法清单：取出条目与版本制品，且忽略未知字段（author/tags/downloadCount 等）")
    void parsesValidManifestIgnoringUnknownFields() {
        PluginCatalogService service = service(false, "");
        String json = """
                {
                  "schemaVersion": "1",
                  "author": "ignored",
                  "entries": [
                    {
                      "pluginId": "stats",
                      "displayNamespace": "stats",
                      "displayNameKey": "plugin.name",
                      "descriptionKey": "plugin.summary",
                      "tags": ["ignored"],
                      "downloadCount": 999,
                      "packages": [
                        {
                          "version": "1.2.3",
                          "packageUrl": "https://example.com/stats-1.2.3.jar",
                          "expectedSizeBytes": 4096,
                          "sha256": "abcdef",
                          "signature": {
                            "formatVersion": 1,
                            "algorithm": "Ed25519",
                            "keyId": "catalog-test-key",
                            "value": "c2ln"
                          },
                          "signatureUrl": "https://example.com/stats-1.2.3.jar.sig",
                          "requiredCoreApi": "1.0",
                          "rating": 4.5
                        }
                      ]
                    }
                  ]
                }
                """;

        PluginCatalogManifest manifest = service.parseManifest(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.entries()).hasSize(1);
        PluginCatalogEntry entry = manifest.entries().get(0);
        assertThat(entry.pluginId()).isEqualTo("stats");
        assertThat(entry.displayNamespace()).isEqualTo("stats");
        assertThat(entry.displayNameKey()).isEqualTo("plugin.name");
        assertThat(entry.descriptionKey()).isEqualTo("plugin.summary");
        assertThat(entry.market()).as("无 market 块时市场元数据为 null").isNull();
        assertThat(entry.packages()).hasSize(1);
        PluginCatalogPackage pkg = entry.packages().get(0);
        assertThat(pkg.version()).isEqualTo("1.2.3");
        assertThat(pkg.packageUrl()).isEqualTo("https://example.com/stats-1.2.3.jar");
        assertThat(pkg.expectedSizeBytes()).isEqualTo(4096L);
        assertThat(pkg.sha256()).isEqualTo("abcdef");
        assertThat(pkg.signature()).isNotNull();
        assertThat(pkg.signature().algorithm()).isEqualTo("Ed25519");
        assertThat(pkg.signatureUrl()).isEqualTo("https://example.com/stats-1.2.3.jar.sig");
        assertThat(pkg.requiredCoreApi()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("旧字符串 signature 协议：解析时拒绝，不静默当作已签名包")
    void rejectsLegacyStringSignature() {
        PluginCatalogService service = service(false, "");
        String json = """
                {
                  "entries": [
                    {
                      "pluginId": "stats",
                      "packages": [
                        {
                          "version": "1.2.3",
                          "packageUrl": "https://example.com/stats-1.2.3.jar",
                          "expectedSizeBytes": 4096,
                          "sha256": "abcdef",
                          "signature": "SIG=="
                        }
                      ]
                    }
                  ]
                }
                """;

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.parseManifest(json.getBytes(StandardCharsets.UTF_8)),
                PluginCatalogException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("空字节 / 空白：解析为空清单（不报错）")
    void parsesEmptyAsEmptyManifest() {
        PluginCatalogService service = service(false, "");
        assertThat(service.parseManifest(new byte[0]).entries()).isEmpty();
        assertThat(service.parseManifest("   ".getBytes(StandardCharsets.UTF_8)).entries()).isEmpty();
    }

    @Test
    @DisplayName("坏 JSON：抛 CATALOG_UNAVAILABLE")
    void malformedJsonRejected() {
        PluginCatalogService service = service(false, "");
        PluginCatalogException ex = catchThrowableOfType(
                () -> service.parseManifest("{ not json ".getBytes(StandardCharsets.UTF_8)),
                PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("启用 + 桩返回合法清单：load() 成功解析")
    void loadFetchesAndParses() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        String json = "{\"entries\":[{\"pluginId\":\"demo\",\"packages\":[]}]}";
        serveSignedManifest("/catalog.json", "configured", json, signing);
        PluginCatalogService service = service(true, CatalogTestSupport.loopbackUrl(server, "/catalog.json"), signing);

        PluginCatalogManifest manifest = service.load();

        assertThat(service.isEnabled()).isTrue();
        assertThat(manifest.findEntry("demo")).isPresent();
    }

    @Test
    @DisplayName("启用 + 桩返回坏 JSON：load() 抛 CATALOG_UNAVAILABLE")
    void loadMalformedIsUnavailable() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        serveSignedManifest("/bad.json", "configured", "<<<", signing);
        PluginCatalogService service = service(true, CatalogTestSupport.loopbackUrl(server, "/bad.json"), signing);

        assertCode(service, PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("启用但目标不可达（连接失败）：load() 抛 CATALOG_UNAVAILABLE")
    void loadNetworkFailureIsUnavailable() {
        // 指向一个未监听的 loopback 端口：连接失败 → DOWNLOAD_FAILED → 归 CATALOG_UNAVAILABLE。
        PluginCatalogService service = service(true, "http://127.0.0.1:1/catalog.json");
        assertCode(service, PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("manifest-url 首尾空白：trim 后正常拉取解析（绝不因 URI.create 抛异常而 500）")
    void loadTrimsSurroundingWhitespaceManifestUrl() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        String json = "{\"entries\":[{\"pluginId\":\"demo\",\"packages\":[]}]}";
        serveSignedManifest("/catalog.json", "configured", json, signing);
        PluginCatalogService service = service(true,
                "  " + CatalogTestSupport.loopbackUrl(server, "/catalog.json") + "  ", signing);

        PluginCatalogManifest manifest = service.load();

        assertThat(service.isEnabled()).isTrue();
        assertThat(manifest.findEntry("demo")).isPresent();
    }

    @Test
    @DisplayName("GitHub blob 清单地址：读取时转为 raw.githubusercontent.com 直连")
    void loadNormalizesGithubBlobManifestUrl() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        String json = "{\"entries\":[{\"pluginId\":\"demo\",\"packages\":[]}]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/repo/blob/master/manifest.json", bytes);
        CatalogTestSupport.serveBytes(server, "/repo/blob/master/manifest.json.sig",
                signing.manifestSignatureBytes("configured", bytes));
        PluginCatalogService service = service(true,
                CatalogTestSupport.loopbackUrl(server, "/repo/blob/master/manifest.json"), signing);

        PluginCatalogManifest manifest = service.load();

        assertThat(PluginCatalogService.normalizedManifestUrl(
                "https://github.com/Sywyar/PixivDownloader-plugins/blob/master/manifest.json"))
                .isEqualTo("https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json");
        assertThat(manifest.findEntry("demo")).isPresent();
    }

    @Test
    @DisplayName("GitHub raw 清单地址：读取时保持直连地址，不追加 raw=1")
    void loadKeepsGithubRawManifestUrl() {
        assertThat(PluginCatalogService.normalizedManifestUrl(
                "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json"))
                .isEqualTo("https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json");
    }

    @Test
    @DisplayName("manifest-url 为坏 URI（含非法空格）：稳定归 CATALOG_UNAVAILABLE，不产生 500")
    void loadMalformedManifestUrlIsUnavailable() {
        // 配置成坏 URI（内含非法空格）：拉取阶段 INSECURE_URL → load() 统一归 CATALOG_UNAVAILABLE，绝不逃逸为 500。
        PluginCatalogService service = service(true, "ht tp://example.com/c.json");
        assertThat(service.isEnabled()).isTrue();
        assertCode(service, PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("解析市场元数据：market 块（本地化名称 / 简介 / 作者 / 来源 / 分类 / 标签 / 主页 / 许可证 / 评分 / 下载量 / token）+ 版本制品发布时间 / 通道 / 下架 / 更新日志 + 清单生成时间")
    void parsesMarketMetadata() {
        PluginCatalogService service = service(false, "");
        String json = """
                {
                  "schemaVersion": "1",
                  "generatedTime": "2026-06-27T00:00:00Z",
                  "entries": [
                    {
                      "pluginId": "stats",
                      "market": {
                        "displayName": {"zh": "统计", "en": "Statistics"},
                        "summary": {"zh": "下载统计仪表盘", "en": "Download stats dashboard"},
                        "description": {"zh": "更详细的说明", "en": "Longer description"},
                        "author": "Sywyar",
                        "sourceType": "official",
                        "category": "utility",
                        "tags": ["stats", "dashboard"],
                        "homepageUrl": "https://github.com/Sywyar/PixivDownloader",
                        "license": "AGPL-3.0-only",
                        "rating": 4.5,
                        "ratingCount": 12,
                        "downloadCount": 1820,
                        "previousDownloadCount": 3500,
                        "totalDownloadCount": 5320,
                        "latestVersion": "1.2.3",
                        "updatedTime": "2026-06-20",
                        "iconToken": "chart-line",
                        "colorToken": "green",
                        "recommended": true,
                        "officialRequired": false
                      },
                      "packages": [
                        {
                          "version": "1.2.3",
                          "packageUrl": "https://example.com/stats-1.2.3.jar",
                          "expectedSizeBytes": 4096,
                          "sha256": "abcdef",
                          "releasedTime": "2026-06-20",
                          "changeNotes": ["fix A", "add B"],
                          "channel": "beta",
                          "deprecated": true
                        }
                      ]
                    }
                  ]
                }
                """;

        PluginCatalogManifest manifest = service.parseManifest(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.generatedTime()).isEqualTo("2026-06-27T00:00:00Z");
        PluginCatalogEntry entry = manifest.entries().get(0);
        PluginCatalogMarketMeta market = entry.market();
        assertThat(market).isNotNull();
        assertThat(market.displayName()).containsEntry("zh", "统计").containsEntry("en", "Statistics");
        assertThat(market.summary()).containsEntry("en", "Download stats dashboard");
        assertThat(market.description()).containsEntry("zh", "更详细的说明");
        assertThat(market.author()).isEqualTo("Sywyar");
        assertThat(market.sourceType()).isEqualTo("official");
        assertThat(market.category()).isEqualTo("utility");
        assertThat(market.tags()).containsExactly("stats", "dashboard");
        assertThat(market.homepageUrl()).isEqualTo("https://github.com/Sywyar/PixivDownloader");
        assertThat(market.license()).isEqualTo("AGPL-3.0-only");
        assertThat(market.rating()).isEqualTo(4.5);
        assertThat(market.ratingCount()).isEqualTo(12);
        assertThat(market.downloadCount()).isEqualTo(1820L);
        assertThat(market.previousDownloadCount()).isEqualTo(3500L);
        assertThat(market.totalDownloadCount()).isEqualTo(5320L);
        assertThat(market.latestVersion()).isEqualTo("1.2.3");
        assertThat(market.iconToken()).isEqualTo("chart-line");
        assertThat(market.recommended()).isTrue();
        PluginCatalogPackage pkg = entry.packages().get(0);
        assertThat(pkg.releasedTime()).isEqualTo("2026-06-20");
        assertThat(pkg.changeNotes()).containsExactly("fix A", "add B");
        assertThat(pkg.channel()).isEqualTo("beta");
        assertThat(pkg.deprecated()).isTrue();
    }

    @Test
    @DisplayName("未知仓库 id：load(repositoryId) 抛 UNKNOWN_REPOSITORY")
    void unknownRepository() {
        PluginCatalogService service = service(true, "");
        PluginCatalogException ex = catchThrowableOfType(
                () -> service.load("ghost"), PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.UNKNOWN_REPOSITORY);
    }

    @Test
    @DisplayName("禁用的仓库：load(repositoryId) 抛 REPOSITORY_DISABLED（不发起拉取）")
    void disabledRepository() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        PluginCatalogProperties.RepositoryConfig repo = new PluginCatalogProperties.RepositoryConfig();
        repo.setId("custom");
        repo.setManifestUrl("https://example.com/custom.json");
        repo.setEnabled(false);
        props.getRepositories().add(repo);
        PluginCatalogService service = new PluginCatalogService(props, relaxed);

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.load("custom"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.REPOSITORY_DISABLED);
    }

    @Test
    @DisplayName("主开关关闭：load(repositoryId) 也抛 CATALOG_DISABLED（主开关优先于仓库解析）")
    void masterOffRejectsRepositoryLoad() {
        PluginCatalogService service = service(false, "");
        PluginCatalogException ex = catchThrowableOfType(
                () -> service.load("official"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.CATALOG_DISABLED);
    }

    @Test
    @DisplayName("按 repositoryId 加载自定义仓库（loopback 桩）：解析成功")
    void loadByRepositoryId() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        String json = "{\"entries\":[{\"pluginId\":\"demo\",\"packages\":[]}]}";
        serveSignedManifest("/c.json", "custom", json, signing);
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        PluginCatalogProperties.RepositoryConfig repo = new PluginCatalogProperties.RepositoryConfig();
        repo.setId("custom");
        repo.setManifestUrl(CatalogTestSupport.loopbackUrl(server, "/c.json"));
        repo.setTrustedKeys(java.util.List.of(signing.trustedKeyConfig()));
        props.getRepositories().add(repo);
        PluginCatalogService service = new PluginCatalogService(props, relaxed);

        PluginCatalogManifest manifest = service.load("custom");

        assertThat(manifest.findEntry("demo")).isPresent();
    }

    @Test
    @DisplayName("自定义仓库信任域隔离：仓库 A 的 key 不能验证仓库 B 的 manifest")
    void customRepositoryKeysAreIsolated() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture repoA = CatalogTestSupport.signingFixture("repo-a-key");
        CatalogTestSupport.SigningFixture repoB = CatalogTestSupport.signingFixture("repo-b-key");
        String aJson = "{\"entries\":[{\"pluginId\":\"demo-a\",\"packages\":[]}]}";
        String bJson = "{\"entries\":[{\"pluginId\":\"demo-b\",\"packages\":[]}]}";
        byte[] aBytes = aJson.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = bJson.getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/a.json", aBytes);
        CatalogTestSupport.serveBytes(server, "/a.json.sig", repoA.manifestSignatureBytes("repo-a", aBytes));
        CatalogTestSupport.serveBytes(server, "/b.json", bBytes);
        CatalogTestSupport.serveBytes(server, "/b.json.sig", repoA.manifestSignatureBytes("repo-b", bBytes));

        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setOfficialRepositoryEnabled(false);
        props.getRepositories().add(repo("repo-a", "/a.json", repoA));
        props.getRepositories().add(repo("repo-b", "/b.json", repoB));
        PluginCatalogService service = new PluginCatalogService(props, relaxed);

        assertThat(service.load("repo-a").findEntry("demo-a")).isPresent();
        PluginCatalogException ex = catchThrowableOfType(() -> service.load("repo-b"),
                PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("自定义仓库无 trustedKeys：manifest 签名 fail-closed，UI 投影同为 UNKNOWN_KEY")
    void customRepositoryWithoutTrustedKeysFailsClosedAndProjectionMatches() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture("repo-a-key");
        byte[] artifact = CatalogTestSupport.explodedPluginZip("demo", "1.0.0", "1.0");
        var packageSignature = signing.artifactSignature("demo", "1.0.0", artifact);
        String json = """
                {"entries":[{"pluginId":"demo","packages":[{
                  "version":"1.0.0",
                  "packageUrl":"http://127.0.0.1/pkg.zip",
                  "expectedSizeBytes":%d,
                  "sha256":"%s",
                  "signature":%s
                }]}]}
                """.formatted(artifact.length, CatalogTestSupport.sha256Hex(artifact),
                signing.signatureJson(packageSignature));
        byte[] manifestBytes = json.getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/custom.json", manifestBytes);
        CatalogTestSupport.serveBytes(server, "/custom.json.sig",
                signing.manifestSignatureBytes("custom", manifestBytes));

        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setOfficialRepositoryEnabled(false);
        PluginCatalogProperties.RepositoryConfig custom = new PluginCatalogProperties.RepositoryConfig();
        custom.setId("custom");
        custom.setManifestUrl(CatalogTestSupport.loopbackUrl(server, "/custom.json"));
        props.getRepositories().add(custom);
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);
        PluginCatalogService service = new PluginCatalogService(registry, repository -> relaxed);

        PluginCatalogException ex = catchThrowableOfType(() -> service.load("custom"),
                PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.CATALOG_UNAVAILABLE);

        PluginRepository repository = registry.find("custom").orElseThrow();
        PluginCatalogPackage pkg = new PluginCatalogPackage("1.0.0", "http://127.0.0.1/pkg.zip",
                (long) artifact.length, CatalogTestSupport.sha256Hex(artifact), packageSignature,
                null, "1.0", List.of(), null, List.of(), null, false);
        assertThat(PluginVerificationProjector.forCatalogPackage(repository, pkg).status())
                .isEqualTo(PluginVerificationProjector.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("自定义仓库显式配置与官方 root 同 keyId 的 key：不继承内置 root，但按该仓库配置可验证")
    void customRepositoryMayExplicitlyTrustOfficialKeyId() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing =
                CatalogTestSupport.signingFixture(PluginTrustStores.builtInOfficialRoot().keyId());
        String json = "{\"entries\":[{\"pluginId\":\"demo\",\"packages\":[]}]}";
        serveSignedManifest("/custom.json", "custom", json, signing);
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setOfficialRepositoryEnabled(false);
        PluginCatalogProperties.RepositoryConfig custom = new PluginCatalogProperties.RepositoryConfig();
        custom.setId("custom");
        custom.setManifestUrl(CatalogTestSupport.loopbackUrl(server, "/custom.json"));
        custom.setTrustedKeys(List.of(signing.trustedKeyConfig()));
        props.getRepositories().add(custom);
        PluginCatalogService service = new PluginCatalogService(props, relaxed);

        PluginCatalogManifest manifest = service.load("custom");

        assertThat(manifest.findEntry("demo")).isPresent();
    }

    private PluginCatalogService service(boolean enabled, String manifestUrl) {
        return service(enabled, manifestUrl, null);
    }

    private PluginCatalogService service(boolean enabled, String manifestUrl,
                                         CatalogTestSupport.SigningFixture signing) {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(enabled);
        props.setManifestUrl(manifestUrl);
        if (signing != null) {
            props.setTrustedKeys(java.util.List.of(signing.trustedKeyConfig()));
        }
        return new PluginCatalogService(props, relaxed);
    }

    private void serveSignedManifest(String path, String repositoryId, String json,
                                     CatalogTestSupport.SigningFixture signing) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, path, bytes);
        CatalogTestSupport.serveBytes(server, path + ".sig", signing.manifestSignatureBytes(repositoryId, bytes));
    }

    private PluginCatalogProperties.RepositoryConfig repo(String id, String path,
                                                          CatalogTestSupport.SigningFixture signing) {
        PluginCatalogProperties.RepositoryConfig repo = new PluginCatalogProperties.RepositoryConfig();
        repo.setId(id);
        repo.setManifestUrl(CatalogTestSupport.loopbackUrl(server, path));
        repo.setTrustedKeys(List.of(signing.trustedKeyConfig()));
        return repo;
    }

    private static void assertCode(PluginCatalogService service, PluginCatalogErrorCode expected) {
        PluginCatalogException ex = catchThrowableOfType(service::load, PluginCatalogException.class);
        assertThat(ex).as("应抛出 PluginCatalogException").isNotNull();
        assertThat(ex.code()).isEqualTo(expected);
    }
}
