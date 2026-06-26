package top.sywyar.pixivdownload.plugin.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 受信 catalog 读取服务：从服务端配置的受信清单地址（{@code plugin-catalog.manifest-url}，仅 https）拉取并解析 catalog
 * manifest。<b>清单地址只来自服务端配置、绝不来自请求参数</b>；拉取经 SSRF 安全的 {@link PluginCatalogHttpClient}、请求
 * <b>字节</b>后按 UTF-8 解码（不请求 {@code String.class}），用 Jackson 解析为 {@link PluginCatalogManifest}（忽略未知
 * 字段、前向兼容）。
 */
@Service
public class PluginCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogService.class);

    private final PluginCatalogProperties properties;
    private final PluginCatalogHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PluginCatalogService(PluginCatalogProperties properties, PluginCatalogHttpClient pluginCatalogHttpClient) {
        this.properties = properties;
        this.httpClient = pluginCatalogHttpClient;
        // 自建 ObjectMapper：显式注册 ParameterNamesModule（record 按构造参数名绑定）+ 忽略未知字段（前向兼容），
        // 不依赖全局 Boot ObjectMapper 的配置，使解析行为在生产与单测中确定一致。
        this.objectMapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** catalog 是否启用（启用且已配置 {@code manifest-url}）。 */
    public boolean isEnabled() {
        return properties.isEnabled() && properties.hasManifestUrl();
    }

    /**
     * 加载受信 catalog 清单。未启用 → {@link PluginCatalogErrorCode#CATALOG_DISABLED}；启用但拉取（含不安全 URL /
     * 阻断地址 / 超限 / 网络失败）或解析失败 → {@link PluginCatalogErrorCode#CATALOG_UNAVAILABLE}。
     */
    public PluginCatalogManifest load() {
        if (!isEnabled()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_DISABLED, "plugin catalog is disabled");
        }
        byte[] bytes;
        try {
            bytes = httpClient.fetchBytes(properties.getManifestUrl(), properties.getMaxManifestBytes());
        } catch (PluginCatalogException e) {
            // 清单地址是服务端配置；拉取阶段任何失败（不安全 URL / 阻断地址 / 超限 / 网络）统一归「catalog 不可用」。
            log.warn("Failed to fetch plugin catalog manifest: {}", e.getMessage());
            throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE,
                    "failed to fetch catalog manifest: " + e.getMessage());
        }
        return parseManifest(bytes);
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
}
