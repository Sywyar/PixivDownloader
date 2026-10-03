package top.sywyar.pixivdownload.plugin.catalog.content;

import jakarta.annotation.PreDestroy;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.sdk.community.content.MarketContent;
import top.sywyar.pixivdownload.sdk.community.content.MarketDocuments;
import top.sywyar.pixivdownload.sdk.community.content.MarketImage;
import top.sywyar.pixivdownload.sdk.community.content.MarketImageBytes;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** 只读取当前受信目录中的展示附件；校验缓存不替代每次的仓库与内容身份解析。 */
public final class PluginCatalogContentService implements AutoCloseable {
    private static final long CACHE_BYTES = 64L * 1024 * 1024;
    private static final int CACHE_ENTRIES = 256;
    private static final long IDLE_NANOS = Duration.ofMinutes(15).toNanos();
    private final PluginCatalogService catalog;
    private final PluginCatalogClientProvider clients;
    private final Semaphore reads = new Semaphore(4);
    private final Map<CacheKey, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private long cachedBytes;
    private volatile boolean closed;

    public PluginCatalogContentService(PluginCatalogService catalog, PluginCatalogClientProvider clients) {
        this.catalog = catalog;
        this.clients = clients;
    }

    public record DocumentView(String html, String sha256, boolean missingResources) { }
    public record ImageView(byte[] bytes, String mediaType, String sha256) { }
    private record CacheKey(PluginRepository repository, MarketContent.Asset asset, String role) { }
    private record Cached(byte[] bytes, long accessed) { }

    public DocumentView document(String repositoryId, String pluginId, String version,
                                 String kind, String locale, String sha256) {
        enter();
        try {
            var selected = catalog.resolvePackage(repositoryId, pluginId, version);
            var content = selected.pkg().content();
            if (content == null) throw unavailable();
            content.validate(null);
            var group = switch (kind) {
                case "readme" -> content.readme();
                case "changelog" -> content.changelog();
                case "releaseNotes" -> content.releaseNotes();
                default -> null;
            };
            var document = group == null ? null : group.get(locale);
            if (document == null || !document.asset().sha256().equals(sha256)) throw unavailable();
            byte[] bytes = bytes(selected.repository(), document.asset(), "document");
            var images = new LinkedHashMap<String, String>();
            boolean missing = false;
            for (String source : MarketDocuments.images(bytes, document.format())) {
                var asset = document.resources() == null ? null : document.resources().get(source);
                if (asset == null) { missing = true; continue; }
                try {
                    byte[] image = bytes(selected.repository(), asset, "screenshot");
                    images.put(source, "data:" + asset.mediaType() + ";base64,"
                            + Base64.getEncoder().encodeToString(image));
                } catch (PluginCatalogException | ContractException failure) {
                    missing = true;
                }
            }
            String html = MarketDocuments.render(bytes, document.format(), images, document.sourceUrl());
            return new DocumentView(html, sha256, missing);
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        } finally { reads.release(); }
    }

    public ImageView image(String repositoryId, String pluginId, String role, int index, String sha256) {
        enter();
        try {
            var repository = catalog.resolveRepository(repositoryId);
            var entry = catalog.loadEntryPage(repositoryId, pluginId, null, 1).item();
            var market = entry.market();
            if (market == null) throw unavailable();
            MarketImage image;
            if ("icon".equals(role) && index == 0) image = market.icon();
            else if ("screenshot".equals(role) && index >= 0 && index < market.screenshots().size()) {
                image = market.screenshots().get(index);
            } else throw unavailable();
            if (image == null || image.asset() == null || !image.asset().sha256().equals(sha256)) throw unavailable();
            var asset = image.asset();
            return new ImageView(bytes(repository, asset, role), asset.mediaType(), sha256);
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        } finally { reads.release(); }
    }

    private void enter() {
        if (closed || !reads.tryAcquire()) throw unavailable();
    }

    private byte[] bytes(PluginRepository repository, MarketContent.Asset asset, String role) {
        boolean image = !"document".equals(role);
        asset.validate(image);
        var key = new CacheKey(repository, asset, role);
        synchronized (cache) {
            expire();
            var prior = cache.get(key);
            if (prior != null) {
                cache.put(key, new Cached(prior.bytes(), System.nanoTime()));
                return prior.bytes();
            }
        }
        byte[] value = clients.clientFor(repository).fetchBytes(asset.url(), asset.size());
        asset.verify(value);
        try {
            if (image && !MarketImageBytes.inspect(value, "icon".equals(role)).mediaType().equals(asset.mediaType())) {
                throw unavailable();
            }
            if (!image) MarketDocuments.text(value);
        } catch (IOException failure) { throw unavailable(); }
        synchronized (cache) {
            if (!closed) {
                var prior = cache.put(key, new Cached(value, System.nanoTime()));
                cachedBytes += value.length - (prior == null ? 0 : prior.bytes().length);
                expire();
            }
        }
        return value;
    }

    private void expire() {
        long now = System.nanoTime();
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue().accessed() < IDLE_NANOS
                    && cachedBytes <= CACHE_BYTES && cache.size() <= CACHE_ENTRIES) break;
            cachedBytes -= entry.getValue().bytes().length;
            iterator.remove();
        }
    }

    private static PluginCatalogException unavailable() {
        return new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED, "market content unavailable");
    }

    @Override
    @PreDestroy
    public void close() {
        closed = true;
        synchronized (cache) { cache.clear(); cachedBytes = 0; }
    }
}
