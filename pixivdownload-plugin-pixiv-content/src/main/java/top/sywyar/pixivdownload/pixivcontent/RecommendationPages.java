package top.sywyar.pixivdownload.pixivcontent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import top.sywyar.pixivdownload.core.web.HttpParameterException;

/** Pixiv supplies the remaining recommendation IDs only in the initial response. */
final class RecommendationPages {
    static final int MAX_IDS = 4096;
    static final int MAX_SEEDS = 128;
    static final long LIFETIME = Duration.ofMinutes(5).toNanos();
    private final LongSupplier clock;
    private final byte[] salt = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();

    RecommendationPages(LongSupplier clock) { this.clock = clock; }

    private record Snapshot(long workId, boolean novel, int limit, String credential, long created, long[] ids) {}
    record Batch(List<Long> ids, String nextCursor) {}

    synchronized String save(long workId, boolean novel, int limit, String cookie, long[] ids) {
        if (ids.length == 0) return null;
        if (ids.length > MAX_IDS) throw ContentFailure.tooLarge();
        long now = clock.getAsLong();
        snapshots.values().removeIf(entry -> now - entry.created >= LIFETIME);
        if (snapshots.size() >= MAX_SEEDS) snapshots.pollFirstEntry();
        String token = UUID.randomUUID().toString();
        snapshots.put(token, new Snapshot(workId, novel, limit, fingerprint(cookie), now, ids));
        return token + ".0";
    }

    synchronized Batch read(String cursor, long workId, boolean novel, int limit, String cookie) {
        if (!cursor.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.[0-9]{1,4}")) throw invalidCursor();
        String token = cursor.substring(0, 36);
        var snapshot = snapshots.get(token);
        if (snapshot == null) throw ContentFailure.expiredCursor();
        if (clock.getAsLong() - snapshot.created >= LIFETIME) {
            snapshots.remove(token);
            throw ContentFailure.expiredCursor();
        }
        if (workId != snapshot.workId || novel != snapshot.novel || limit != snapshot.limit
                || !fingerprint(cookie).equals(snapshot.credential)) throw invalidCursor();
        int start = Integer.parseInt(cursor.substring(37));
        if (start >= snapshot.ids.length || start % limit != 0) throw invalidCursor();
        int end = Math.min(start + limit, snapshot.ids.length);
        List<Long> ids = java.util.Arrays.stream(snapshot.ids, start, end).boxed().toList();
        return new Batch(ids, end < snapshot.ids.length ? token + "." + end : null);
    }

    private static HttpParameterException invalidCursor() { return new HttpParameterException("query", "cursor", "invalid_value"); }

    private String fingerprint(String cookie) {
        if (cookie == null) return "";
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(salt);
            return HexFormat.of().formatHex(hash.digest(cookie.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
