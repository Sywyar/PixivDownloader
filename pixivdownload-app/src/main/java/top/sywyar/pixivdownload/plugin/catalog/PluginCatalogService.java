package top.sywyar.pixivdownload.plugin.catalog;

import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogManifest;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.page.PagedCatalogDocument;
import top.sywyar.pixivdownload.plugin.catalog.page.PagedCatalogItemDocument;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogDetailPage;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPage;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPageQuery;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.signature.ManifestVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受信 catalog 读取服务：从<b>服务端配置的仓库列表</b>（{@link PluginRepositoryRegistry}，内嵌官方默认仓库 + 自定义仓库，
 * 仅 https）解析 catalog manifest。<b>清单地址只来自服务端配置 / 内嵌常量、绝不来自请求参数</b>；按仓库代理策略经
 * {@link PluginCatalogClientProvider} 取得 SSRF 安全的 {@link PluginCatalogHttpClient}，请求<b>字节</b>后按 UTF-8 解码
 * （不请求 {@code String.class}），用 Jackson 解析为 {@link PluginCatalogManifest}（忽略未知字段、前向兼容）。
 *
 * <p>主开关（{@code plugin-catalog.enabled}）关闭时整体不可用、不联网；开启后默认操作内嵌官方仓库（除非配置了旧版
 * {@code manifest-url} 兼容仓库），也可按 {@code repositoryId} 操作指定仓库。
 */
@Service
public class PluginCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogService.class);
    private static final int MAX_ENTRY_PAGES = 10;
    private static final int ENTRY_PAGE_SIZE = 100;
    private static final Pattern GITHUB_BLOB_MANIFEST_URL = Pattern.compile(
            "^https://github\\.com/([^/?#]+)/([^/?#]+)/blob/([^/?#]+)/(.+\\.json)(?:[?#].*)?$");

    private final PluginRepositoryRegistry repositoryRegistry;
    private final PluginCatalogClientProvider clientProvider;
    private final ObjectMapper objectMapper;
    private final ObjectMapper strictObjectMapper = PluginCatalogStrictJson.mapper(false);
    private final Function<PluginRepository, PluginSupplyChainVerifier> verifierResolver;
    private final Map<String, CachedPage> pageCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
            return size() > 64;
        }
    };
    private final Map<String, CachedDetailPage> detailPageCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedDetailPage> eldest) {
            return size() > 64;
        }
    };
    private final Map<String, String> generations = new java.util.concurrent.ConcurrentHashMap<>();
    private final Semaphore pagedRequests = new Semaphore(4);

    @Autowired
    public PluginCatalogService(PluginRepositoryRegistry repositoryRegistry,
                                PluginCatalogClientProvider clientProvider) {
        this(repositoryRegistry, clientProvider, PluginCatalogTrustStores::verifierForRepository);
    }

    public PluginCatalogService(PluginRepositoryRegistry repositoryRegistry,
                                PluginCatalogClientProvider clientProvider,
                                PluginSupplyChainVerifier verifier) {
        this(repositoryRegistry, clientProvider, repository -> verifier);
    }

    public PluginCatalogService(PluginRepositoryRegistry repositoryRegistry,
                                PluginCatalogClientProvider clientProvider,
                                Function<PluginRepository, PluginSupplyChainVerifier> verifierResolver) {
        this.repositoryRegistry = repositoryRegistry;
        this.clientProvider = clientProvider;
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        // 自建 ObjectMapper：显式注册 ParameterNamesModule（record 按构造参数名绑定）+ 忽略未知字段（前向兼容），
        // 不依赖全局 Boot ObjectMapper 的配置，使解析行为在生产与单测中确定一致。
        this.objectMapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 便利构造（测试 / 简单装配）：从配置直接建仓库注册中心，并用一个固定客户端服务所有仓库（对接 loopback 桩）。
     * 生产装配走 {@link #PluginCatalogService(PluginRepositoryRegistry, PluginCatalogClientProvider)}。
     */
    public PluginCatalogService(PluginCatalogProperties properties, PluginCatalogHttpClient httpClient) {
        this(new PluginRepositoryRegistry(properties), repository -> httpClient);
    }

    /** catalog 是否启用（主开关开启且存在可用的默认仓库）。 */
    public boolean isEnabled() {
        return repositoryRegistry.featureEnabled() && repositoryRegistry.defaultRepository().isPresent();
    }

    /**
     * 加载默认仓库的受信 catalog 清单。主开关关闭 / 无可用仓库 → {@link PluginCatalogErrorCode#CATALOG_DISABLED}；
     * 拉取（含不安全 URL / 阻断地址 / 超限 / 网络失败）或解析失败 → {@link PluginCatalogErrorCode#CATALOG_UNAVAILABLE}。
     */
    public PluginCatalogManifest load() {
        PluginRepository repository = resolveDefaultRepository();
        return load(repository.repositoryId());
    }

    /**
     * 按 {@code repositoryId} 加载指定仓库的清单。主开关关闭 → {@code CATALOG_DISABLED}；未知 id →
     * {@link PluginCatalogErrorCode#UNKNOWN_REPOSITORY}；仓库禁用 → {@link PluginCatalogErrorCode#REPOSITORY_DISABLED}；
     * 代理策略不支持 → {@link PluginCatalogErrorCode#PROXY_POLICY_UNSUPPORTED}；拉取 / 解析失败 → {@code CATALOG_UNAVAILABLE}。
     */
    public PluginCatalogManifest load(String repositoryId) {
        PluginRepository repository = resolveRepository(repositoryId);
        if (repository.pagedCatalog()) {
            PluginCatalogPage page = loadPage(repositoryId,
                    new PluginCatalogPageQuery(null, 100, null, null, null, null));
            return new PluginCatalogManifest("paged-v2", page.generation(), page.items());
        }
        return loadRepository(repository);
    }

    /** 按仓库协议返回一页；manifest-v1 在已验签清单内存分页，paged-v2 发有界条件请求。 */
    public PluginCatalogPage loadPage(String repositoryId, PluginCatalogPageQuery query) {
        PluginRepository repository = resolveRepository(repositoryId);
        PluginCatalogPageQuery effective = query != null ? query : PluginCatalogPageQuery.first();
        return repository.pagedCatalog() ? loadPaged(repository, effective) : pageManifest(loadRepository(repository), effective);
    }

    public PluginCatalogEntry loadEntry(String repositoryId, String pluginId) {
        PluginRepository repository = resolveRepository(repositoryId);
        if (!repository.pagedCatalog()) {
            return loadRepository(repository).findEntry(pluginId)
                    .orElseThrow(() -> unknownPlugin(pluginId));
        }
        String cursor = null;
        String generation = null;
        PluginCatalogEntry entry = null;
        Map<String, PluginCatalogPackage> packages = new LinkedHashMap<>();
        for (int pageNumber = 0; pageNumber < MAX_ENTRY_PAGES; pageNumber++) {
            PluginCatalogDetailPage page = loadEntryPage(repositoryId, pluginId, cursor, ENTRY_PAGE_SIZE);
            if (generation != null && !generation.equals(page.generation())) {
                packages.clear();
                entry = null;
            }
            generation = page.generation();
            entry = page.item();
            entry.packages().forEach(pkg -> packages.putIfAbsent(pkg.version(), pkg));
            cursor = page.nextCursor();
            if (cursor == null) {
                return new PluginCatalogEntry(entry.pluginId(), entry.displayNamespace(), entry.displayNameKey(),
                        entry.descriptionKey(), entry.market(), List.copyOf(packages.values()));
            }
        }
        throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                "paged catalog entry exceeded the dependency resolution page limit");
    }

    /** 返回单个插件的一页版本摘要；paged-v2 cursor 保持不透明，manifest-v1 保持既有完整详情。 */
    public PluginCatalogDetailPage loadEntryPage(String repositoryId, String pluginId, String cursor, int limit) {
        PluginRepository repository = resolveRepository(repositoryId);
        if (!repository.pagedCatalog()) {
            PluginCatalogEntry entry = loadRepository(repository).findEntry(pluginId)
                    .orElseThrow(() -> unknownPlugin(pluginId));
            return new PluginCatalogDetailPage(entry, "manifest-v1", null,
                    (long) entry.packages().size(), false);
        }
        requirePathToken(pluginId, "pluginId");
        PluginCatalogPageQuery query = new PluginCatalogPageQuery(cursor, limit, null, null, null, null);
        List<String> parameters = new ArrayList<>();
        parameters.add("limit=" + query.limit());
        addParameter(parameters, "cursor", query.cursor());
        String url = endpoint(repository) + "/plugins/" + encode(pluginId) + '?' + String.join("&", parameters);
        CachedDetailPage cached;
        synchronized (detailPageCache) { cached = detailPageCache.get(url); }
        try {
            PluginCatalogHttpClient.FetchResult response = fetchPaged(repository, url, 256L * 1024L,
                    cached != null ? cached.etag() : null);
            if (response.statusCode() == 304) {
                if (cached != null && currentGeneration(repository, cached.page().generation())) {
                    return cached.page();
                }
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "paged catalog returned 304 for a discarded generation");
            }
            PagedCatalogItemDocument document = parseJson(response.bytes(), PagedCatalogItemDocument.class);
            if (document == null || document.generation() == null || document.generation().isBlank()
                    || document.item() == null || !pluginId.equals(document.item().pluginId())) {
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "paged catalog detail identity mismatch");
            }
            if (document.item().packages().size() > query.limit()
                    || document.totalApproximate() != null && document.totalApproximate() < 0L) {
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "paged catalog detail exceeded its bounds");
            }
            validateEntrySignatures(document.item());
            String previousGeneration = generations.putIfAbsent(repository.repositoryId(), document.generation());
            if (query.cursor() != null && previousGeneration != null
                    && !previousGeneration.equals(document.generation())) {
                generations.put(repository.repositoryId(), document.generation());
                return loadEntryPage(repositoryId, pluginId, null, query.limit());
            }
            rememberGeneration(repository, document.generation());
            PluginCatalogDetailPage loaded = new PluginCatalogDetailPage(document.item(), document.generation(),
                    boundedCursor(document.nextCursor()), document.totalApproximate(), false);
            synchronized (detailPageCache) {
                detailPageCache.put(url, new CachedDetailPage(response.etag(), loaded));
            }
            return loaded;
        } catch (PluginCatalogException failure) {
            if (cached != null && currentGeneration(repository, cached.page().generation())) {
                return cached.page().staleCopy();
            }
            throw failure;
        }
    }

    /** 安装路径在下载前按协议重新解析版本，绝不信任浏览页缓存或客户端 URL。 */
    ResolvedPackage resolvePackage(String repositoryId, String pluginId, String version) {
        PluginRepository repository = resolveRepository(repositoryId);
        if (!repository.pagedCatalog()) {
            PluginCatalogEntry entry = loadRepository(repository).findEntry(pluginId)
                    .orElseThrow(() -> unknownPlugin(pluginId));
            PluginCatalogPackage pkg = entry.findPackage(version).orElseThrow(() -> versionMissing(pluginId, version));
            return new ResolvedPackage(repository, entry, pkg);
        }
        requirePathToken(pluginId, "pluginId");
        requirePathToken(version, "version");
        String url = endpoint(repository) + "/plugins/" + encode(pluginId) + "/versions/" + encode(version);
        PagedCatalogItemDocument document = parseJson(
                fetchPaged(repository, url, 256L * 1024L, null).bytes(), PagedCatalogItemDocument.class);
        PluginCatalogPackage pkg = document != null ? document.version() : null;
        if (document == null || document.generation() == null || document.generation().isBlank()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "paged exact-version response generation is missing");
        }
        if (pkg == null || !version.equals(pkg.version())) throw versionMissing(pluginId, version);
        validatePackageSignature(pkg, pluginId);
        rememberGeneration(repository, document.generation());
        return new ResolvedPackage(repository, null, pkg);
    }

    ResolvedPackage resolveDefaultPackage(String pluginId, String version) {
        return resolvePackage(resolveDefaultRepository().repositoryId(), pluginId, version);
    }

    /**
     * 包内安装编排入口：解析默认仓库并加载其清单，一次返回确切仓库 + manifest，保证后续包下载与清单读取同源。
     * 主开关关闭 / 无可用仓库 → {@link PluginCatalogErrorCode#CATALOG_DISABLED}。
     */
    ResolvedCatalog loadResolvedDefault() {
        PluginRepository repository = resolveDefaultRepository();
        return new ResolvedCatalog(repository, load(repository.repositoryId()));
    }

    /**
     * 包内安装编排入口：按 {@code repositoryId} 解析受控仓库并加载其清单，一次返回确切仓库 + manifest。
     * 主开关关闭 → {@code CATALOG_DISABLED}；未知 id → {@link PluginCatalogErrorCode#UNKNOWN_REPOSITORY}；仓库禁用 →
     * {@link PluginCatalogErrorCode#REPOSITORY_DISABLED}。
     */
    ResolvedCatalog loadResolved(String repositoryId) {
        PluginRepository repository = resolveRepository(repositoryId);
        return new ResolvedCatalog(repository, load(repository.repositoryId()));
    }

    private PluginRepository resolveDefaultRepository() {
        requireFeatureEnabled();
        return repositoryRegistry.defaultRepository().orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.CATALOG_DISABLED, "no enabled plugin repository"));
    }

    private PluginRepository resolveRepository(String repositoryId) {
        requireFeatureEnabled();
        PluginRepository repository = repositoryRegistry.find(repositoryId).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.UNKNOWN_REPOSITORY,
                        "unknown plugin repository: " + repositoryId));
        if (!repository.enabled()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DISABLED,
                    "plugin repository is disabled: " + repository.repositoryId());
        }
        return repository;
    }

    private void requireFeatureEnabled() {
        if (!repositoryRegistry.featureEnabled()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_DISABLED, "plugin catalog is disabled");
        }
    }

    /**
     * 拉取并解析某仓库的清单。代理策略不支持时 {@link PluginCatalogClientProvider#clientFor} 抛
     * {@code PROXY_POLICY_UNSUPPORTED}（在拉取前、直接传播）；拉取阶段（不安全 URL / 阻断地址 / 超限 / 网络）任何失败
     * 统一归 {@code CATALOG_UNAVAILABLE}（清单地址是服务端配置，不暴露具体失败给请求方）。
     */
    private PluginCatalogManifest loadRepository(PluginRepository repository) {
        PluginCatalogHttpClient httpClient = clientProvider.clientFor(repository);
        byte[] bytes;
        byte[] signatureBytes;
        try {
            String manifestUrl = normalizedManifestUrl(repository.manifestUrl());
            bytes = httpClient.fetchBytes(manifestUrl, repository.maxManifestBytes());
            signatureBytes = httpClient.fetchBytes(detachedManifestSignatureUrl(manifestUrl),
                    Math.min(16 * 1024, repository.maxManifestBytes()));
        } catch (PluginCatalogException e) {
            log.warn("Failed to fetch plugin catalog manifest from repository {}: {}",
                    repository.repositoryId(), e.getMessage());
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "failed to fetch catalog manifest: " + e.getMessage());
        }
        SignatureMetadata signature = parseSignatureMetadata(signatureBytes);
        VerificationResult result = verifierFor(repository).verifyManifest(new ManifestVerificationRequest(bytes,
                repository.repositoryId(), signature, policy(repository)));
        if (!result.accepted()) {
            log.warn("Plugin catalog manifest signature verification failed for repository {}: {} ({})",
                    repository.repositoryId(), result.status(), result.diagnosticCode());
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "catalog manifest signature verification failed: " + result.status());
        }
        PluginCatalogManifest manifest = parseManifest(bytes);
        validatePackageSignatures(manifest);
        return manifest;
    }

    private PluginSupplyChainVerifier verifierFor(PluginRepository repository) {
        return Objects.requireNonNull(verifierResolver.apply(repository), "verifierResolver returned null");
    }

    /** 包内受控编排结果；当前安装路径只消费本服务完成主开关 / id / 启用状态校验后产生的实例。 */
    record ResolvedCatalog(PluginRepository repository, PluginCatalogManifest manifest) {
    }

    record ResolvedPackage(PluginRepository repository, PluginCatalogEntry entry, PluginCatalogPackage pkg) { }

    private PluginCatalogPage loadPaged(PluginRepository repository, PluginCatalogPageQuery query) {
        String url = pageUrl(repository, query);
        CachedPage cached;
        synchronized (pageCache) { cached = pageCache.get(url); }
        boolean restartAtFirstPage = false;
        PluginCatalogPage loaded = null;
        try {
            PluginCatalogHttpClient.FetchResult response = fetchPaged(repository, url, 512L * 1024L,
                    cached != null ? cached.etag() : null);
            if (response.statusCode() == 304) {
                if (cached != null && currentGeneration(repository, cached.page().generation())) {
                    return cached.page();
                }
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "paged catalog returned 304 for a discarded generation");
            }
            PagedCatalogDocument document = parseJson(response.bytes(), PagedCatalogDocument.class);
            if (document == null || document.generation() == null || document.generation().isBlank()
                    || document.items() == null || document.items().size() > query.limit()
                    || document.totalApproximate() != null && document.totalApproximate() < 0L
                    || document.facets() != null && document.facets().size() > 64) {
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "invalid paged catalog response");
            }
            Set<String> pluginIds = new HashSet<>();
            for (PluginCatalogEntry entry : document.items()) {
                validateEntrySignatures(entry);
                if (!pluginIds.add(entry.pluginId())) {
                    throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                            "paged catalog response contains duplicate plugin ids");
                }
            }
            validateFacets(document.facets());
            String expectedGeneration = generations.putIfAbsent(repository.repositoryId(), document.generation());
            if (query.cursor() != null && expectedGeneration != null
                    && !expectedGeneration.equals(document.generation())) {
                generations.put(repository.repositoryId(), document.generation());
                restartAtFirstPage = true;
            } else {
                generations.put(repository.repositoryId(), document.generation());
                loaded = new PluginCatalogPage(document.generation(), document.items(),
                        boundedCursor(document.nextCursor()), document.totalApproximate(), document.facets(), false);
                synchronized (pageCache) { pageCache.put(url, new CachedPage(response.etag(), loaded)); }
            }
        } catch (PluginCatalogException failure) {
            if (cached != null && currentGeneration(repository, cached.page().generation())) {
                return cached.page().staleCopy();
            }
            throw failure.code() == PluginCatalogErrorCode.CATALOG_UNAVAILABLE ? failure
                    : new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE, failure.getMessage());
        }
        return restartAtFirstPage ? loadPaged(repository, query.firstPage()) : loaded;
    }

    private PluginCatalogPage pageManifest(PluginCatalogManifest manifest, PluginCatalogPageQuery query) {
        List<PluginCatalogEntry> filtered = manifest.entries().stream().filter(entry -> matches(entry, query)).toList();
        int offset = decodeCursor(query.cursor());
        if (offset > filtered.size()) offset = 0;
        int end = Math.min(filtered.size(), offset + query.limit());
        String next = end < filtered.size() ? encodeCursor(end) : null;
        return new PluginCatalogPage(manifest.generatedTime() != null ? manifest.generatedTime() : "manifest-v1",
                filtered.subList(offset, end), next, (long) filtered.size(), Map.of(), false);
    }

    private static boolean matches(PluginCatalogEntry entry, PluginCatalogPageQuery query) {
        String needle = lower(query.query());
        if (needle != null) {
            String searchable = (entry.pluginId() + " " + entry.displayNameKey() + " " + entry.descriptionKey()
                    + " " + (entry.market() != null ? entry.market().author() + " " + entry.market().tags() : ""))
                    .toLowerCase(Locale.ROOT);
            if (!searchable.contains(needle)) return false;
        }
        if (query.category() != null && (entry.market() == null
                || !query.category().equals(entry.market().category()))) return false;
        if (query.publisher() != null && (entry.market() == null
                || !query.publisher().equalsIgnoreCase(entry.market().author()))) return false;
        return query.channel() == null || entry.packages().stream()
                .anyMatch(pkg -> query.channel().equalsIgnoreCase(pkg.channel()));
    }

    private PluginCatalogHttpClient.FetchResult fetchPaged(PluginRepository repository, String url,
                                                            long maxBytes, String etag) {
        boolean acquired = false;
        try {
            acquired = pagedRequests.tryAcquire();
            if (!acquired) {
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "paged catalog concurrency limit reached");
            }
            return clientProvider.clientFor(repository).fetch(url, maxBytes, etag);
        } catch (PluginCatalogException failure) {
            throw failure.code() == PluginCatalogErrorCode.CATALOG_UNAVAILABLE ? failure
                    : new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE, failure.getMessage());
        } finally {
            if (acquired) pagedRequests.release();
        }
    }

    private <T> T parseJson(byte[] bytes, Class<T> type) {
        try {
            return strictObjectMapper.readValue(PluginCatalogStrictJson.strictUtf8(bytes), type);
        } catch (Exception failure) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "malformed paged catalog response: " + failure.getMessage());
        }
    }

    private static String pageUrl(PluginRepository repository, PluginCatalogPageQuery query) {
        List<String> parameters = new ArrayList<>();
        parameters.add("limit=" + query.limit());
        addParameter(parameters, "cursor", query.cursor());
        addParameter(parameters, "query", query.query());
        addParameter(parameters, "category", query.category());
        addParameter(parameters, "publisher", query.publisher());
        addParameter(parameters, "channel", query.channel());
        return endpoint(repository) + "/plugins?" + String.join("&", parameters);
    }

    private static void addParameter(List<String> parameters, String name, String value) {
        if (value != null) parameters.add(name + '=' + encode(value));
    }

    private static String endpoint(PluginRepository repository) {
        String endpoint = repository.catalogEndpoint();
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String boundedCursor(String cursor) {
        if (cursor == null) return null;
        if (cursor.length() > 512 || cursor.codePoints().anyMatch(Character::isISOControl)) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "paged catalog returned an invalid cursor");
        }
        return cursor;
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(offset)
                .getBytes(StandardCharsets.US_ASCII));
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    private static String lower(String value) { return value == null ? null : value.toLowerCase(Locale.ROOT); }

    private static void requirePathToken(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._+\\-]{1,128}")) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    field + " is invalid");
        }
    }

    private void rememberGeneration(PluginRepository repository, String generation) {
        if (generation != null && !generation.isBlank()) generations.put(repository.repositoryId(), generation);
    }

    private boolean currentGeneration(PluginRepository repository, String generation) {
        String current = generations.get(repository.repositoryId());
        return current == null || current.equals(generation);
    }

    private static PluginCatalogException unknownPlugin(String pluginId) {
        return new PluginCatalogException(PluginCatalogErrorCode.UNKNOWN_PLUGIN, pluginId, null,
                "plugin not found in catalog: " + pluginId);
    }

    private static PluginCatalogException versionMissing(String pluginId, String version) {
        return new PluginCatalogException(PluginCatalogErrorCode.VERSION_NOT_FOUND, pluginId, version,
                "version not found in catalog: " + pluginId + " " + version);
    }

    private record CachedPage(String etag, PluginCatalogPage page) { }

    private record CachedDetailPage(String etag, PluginCatalogDetailPage page) { }

    /** 解析清单字节（UTF-8 + Jackson）。空 → 空清单；坏 JSON → {@link PluginCatalogErrorCode#CATALOG_UNAVAILABLE}。 */
    PluginCatalogManifest parseManifest(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return PluginCatalogManifest.empty();
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return PluginCatalogManifest.empty();
        }
        try {
            PluginCatalogManifest manifest = objectMapper.readValue(json, PluginCatalogManifest.class);
            return manifest != null ? manifest : PluginCatalogManifest.empty();
        } catch (Exception e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "malformed catalog manifest: " + e.getMessage());
        }
    }

    private SignatureMetadata parseSignatureMetadata(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "missing catalog manifest signature");
        }
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), SignatureMetadata.class);
        } catch (Exception e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "malformed catalog manifest signature: " + e.getMessage());
        }
    }

    private static VerificationPolicy policy(PluginRepository repository) {
        return repository.official() ? VerificationPolicy.officialRepository() : VerificationPolicy.customRepository();
    }

    private static void validatePackageSignatures(PluginCatalogManifest manifest) {
        for (PluginCatalogEntry entry : manifest.entries()) {
            for (PluginCatalogPackage pkg : entry.packages()) {
                if (!pkg.hasSignature()) {
                    throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                            "catalog package is missing publisher signature: "
                                    + entry.pluginId() + " " + pkg.version());
                }
            }
        }
    }

    private static void validateEntrySignatures(PluginCatalogEntry entry) {
        if (entry == null || entry.pluginId() == null || entry.pluginId().isBlank()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "paged catalog item identity is missing");
        }
        requirePathToken(entry.pluginId(), "pluginId");
        Set<String> versions = new HashSet<>();
        for (PluginCatalogPackage pkg : entry.packages()) {
            validatePackageSignature(pkg, entry.pluginId());
            if (!versions.add(pkg.version())) {
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "paged catalog item contains duplicate versions: " + entry.pluginId());
            }
        }
    }

    private static void validatePackageSignature(PluginCatalogPackage pkg, String pluginId) {
        if (pkg == null || pkg.version() == null || pkg.version().isBlank() || !pkg.hasSignature()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "catalog package identity or publisher signature is missing: " + pluginId);
        }
        requirePathToken(pkg.version(), "version");
    }

    private static void validateFacets(Map<String, Long> facets) {
        if (facets == null) return;
        for (Map.Entry<String, Long> facet : facets.entrySet()) {
            String name = facet.getKey();
            if (name == null || name.isBlank() || name.length() > 64
                    || name.codePoints().anyMatch(Character::isISOControl)
                    || facet.getValue() == null || facet.getValue() < 0L) {
                throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                        "paged catalog facets are invalid");
            }
        }
    }

    private static String detachedManifestSignatureUrl(String manifestUrl) {
        String normalized = normalizedManifestUrl(manifestUrl);
        int query = normalized.indexOf('?');
        if (query >= 0) {
            return normalized.substring(0, query) + ".sig" + normalized.substring(query);
        }
        return normalized + ".sig";
    }

    static String normalizedManifestUrl(String manifestUrl) {
        String url = manifestUrl == null ? "" : manifestUrl.trim();
        Matcher githubBlob = GITHUB_BLOB_MANIFEST_URL.matcher(url);
        if (githubBlob.matches()) {
            return "https://raw.githubusercontent.com/"
                    + githubBlob.group(1) + "/"
                    + githubBlob.group(2) + "/"
                    + githubBlob.group(3) + "/"
                    + githubBlob.group(4);
        }
        return url;
    }

}
