package top.sywyar.pixivdownload.plugin.catalog;

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

import java.nio.charset.StandardCharsets;
import java.util.Objects;
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
    private static final Pattern GITHUB_BLOB_MANIFEST_URL = Pattern.compile(
            "^https://github\\.com/([^/?#]+)/([^/?#]+)/blob/([^/?#]+)/(.+\\.json)(?:[?#].*)?$");

    private final PluginRepositoryRegistry repositoryRegistry;
    private final PluginCatalogClientProvider clientProvider;
    private final ObjectMapper objectMapper;
    private final Function<PluginRepository, PluginSupplyChainVerifier> verifierResolver;

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
        return loadResolvedDefault().manifest();
    }

    /**
     * 按 {@code repositoryId} 加载指定仓库的清单。主开关关闭 → {@code CATALOG_DISABLED}；未知 id →
     * {@link PluginCatalogErrorCode#UNKNOWN_REPOSITORY}；仓库禁用 → {@link PluginCatalogErrorCode#REPOSITORY_DISABLED}；
     * 代理策略不支持 → {@link PluginCatalogErrorCode#PROXY_POLICY_UNSUPPORTED}；拉取 / 解析失败 → {@code CATALOG_UNAVAILABLE}。
     */
    public PluginCatalogManifest load(String repositoryId) {
        return loadResolved(repositoryId).manifest();
    }

    /**
     * 包内安装编排入口：解析默认仓库并加载其清单，一次返回确切仓库 + manifest，保证后续包下载与清单读取同源。
     * 主开关关闭 / 无可用仓库 → {@link PluginCatalogErrorCode#CATALOG_DISABLED}。
     */
    ResolvedCatalog loadResolvedDefault() {
        PluginRepository repository = resolveDefaultRepository();
        return new ResolvedCatalog(repository, loadRepository(repository));
    }

    /**
     * 包内安装编排入口：按 {@code repositoryId} 解析受控仓库并加载其清单，一次返回确切仓库 + manifest。
     * 主开关关闭 → {@code CATALOG_DISABLED}；未知 id → {@link PluginCatalogErrorCode#UNKNOWN_REPOSITORY}；仓库禁用 →
     * {@link PluginCatalogErrorCode#REPOSITORY_DISABLED}。
     */
    ResolvedCatalog loadResolved(String repositoryId) {
        PluginRepository repository = resolveRepository(repositoryId);
        return new ResolvedCatalog(repository, loadRepository(repository));
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
        validatePackageSignatures(repository, manifest);
        return manifest;
    }

    private PluginSupplyChainVerifier verifierFor(PluginRepository repository) {
        return Objects.requireNonNull(verifierResolver.apply(repository), "verifierResolver returned null");
    }

    /** 包内受控编排结果；当前安装路径只消费本服务完成主开关 / id / 启用状态校验后产生的实例。 */
    record ResolvedCatalog(PluginRepository repository, PluginCatalogManifest manifest) {
    }

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

    private static void validatePackageSignatures(PluginRepository repository, PluginCatalogManifest manifest) {
        if (!repository.official()) {
            return;
        }
        for (PluginCatalogEntry entry : manifest.entries()) {
            for (PluginCatalogPackage pkg : entry.packages()) {
                if (!pkg.hasSignature()) {
                    throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                            "official catalog package is missing signature: "
                                    + entry.pluginId() + " " + pkg.version());
                }
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
