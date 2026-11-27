package top.sywyar.pixivdownload.pixivcontent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;

/** Short-lived page metadata only. This cache never holds authentication material or image bytes. */
final class PageManifestCache {
    static final long TTL_NANOS = Duration.ofSeconds(30).toNanos();
    static final int MAX_MANIFESTS = 64;
    static final int MAX_WEIGHT = 2 * 1024 * 1024;
    static final int MAX_IN_FLIGHT = 16;
    private final byte[] salt = new byte[32];
    private final LongSupplier nanoTime;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<Key, CompletableFuture<Manifest>> loading = new HashMap<>();
    private int weight;

    PageManifestCache(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        new SecureRandom().nextBytes(salt);
    }

    record Page(String preview, String original, Integer width, Integer height) {}
    record Manifest(List<Page> pages, long weight) {
        Manifest(List<Page> pages) {
            this(List.copyOf(pages), 256L + pages.stream().mapToLong(page ->
                    128L + 2L * (page.preview().length() + (long) page.original().length())).sum());
        }
    }
    private record Key(long artworkId, String digest, boolean selected) {}
    private record Entry(Manifest manifest, long savedAt) {}
    record Read(Key key, Manifest manifest, boolean cached) {}

    Read read(long artworkId, String cookie, Supplier<Manifest> fetch) {
        var scope = AcquisitionCredentials.current();
        if (scope != null) scope.checkCancelled();
        // The Ajax port can switch accounts on retry and does not expose the winning account.
        // Only a single, known credential (or a genuinely anonymous request) may reuse a manifest.
        var key = key(artworkId, cookie);
        if (key == null) return new Read(null, fetch.get(), false);
        CompletableFuture<Manifest> flight;
        boolean owner = false;
        synchronized (this) {
            var found = entries.get(key);
            if (found != null) {
                if (nanoTime.getAsLong() - found.savedAt < TTL_NANOS) return new Read(key, found.manifest, true);
                remove(key);
            }
            flight = loading.get(key);
            if (flight == null && loading.size() < MAX_IN_FLIGHT) {
                flight = new CompletableFuture<>();
                loading.put(key, flight);
                owner = true;
            }
        }
        if (flight == null) return new Read(null, fetch.get(), false);
        if (!owner) {
            var manifest = await(flight);
            if (scope != null) scope.checkCancelled();
            return new Read(key, manifest, false);
        }
        try {
            var manifest = fetch.get();
            if (scope != null) scope.checkCancelled();
            synchronized (this) {
                if (loading.get(key) != flight) return new Read(key, await(flight), false);
                if (manifest.weight <= MAX_WEIGHT) {
                    while (entries.size() >= MAX_MANIFESTS || weight + manifest.weight > MAX_WEIGHT) {
                        remove(entries.firstEntry().getKey());
                    }
                    entries.put(key, new Entry(manifest, nanoTime.getAsLong()));
                    weight += (int) manifest.weight;
                }
                loading.remove(key, flight);
                flight.complete(manifest);
            }
            return new Read(key, manifest, false);
        } catch (RuntimeException | Error failure) {
            synchronized (this) { loading.remove(key, flight); }
            flight.completeExceptionally(failure);
            throw failure;
        }
    }

    synchronized void invalidate(Read read) {
        Entry entry = entries.get(read.key);
        // A delayed CDN failure must not evict a newer manifest published by another request.
        if (entry != null && entry.manifest == read.manifest) remove(read.key);
    }

    void denied(long artworkId, String credential, RuntimeException rejection) {
        Key key = key(artworkId, credential);
        if (key == null) return;
        synchronized (this) {
            if (entries.containsKey(key)) remove(key);
            var pending = loading.remove(key);
            if (pending != null) pending.completeExceptionally(rejection);
        }
    }

    private Key key(long artworkId, String cookie) {
        if (AcquisitionCredentials.candidates(cookie).size() > 1) return null;
        var scope = AcquisitionCredentials.current();
        return new Key(artworkId, cookie == null ? null : digest(cookie),
                scope != null && scope.primary().equals(cookie));
    }

    private void remove(Key key) {
        weight -= (int) entries.remove(key).manifest.weight;
    }

    private String digest(String credential) {
        byte[] bytes = credential.getBytes(StandardCharsets.UTF_8);
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(salt);
            return HexFormat.of().formatHex(hash.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static Manifest await(CompletableFuture<Manifest> flight) {
        try {
            return flight.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Pixiv page request interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Error error) throw error;
            throw (RuntimeException) failed.getCause();
        }
    }
}
