package top.sywyar.pixivdownload.duplicate;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.hash.ImageHashMapper;
import top.sywyar.pixivdownload.core.hash.ImageHashRow;
import top.sywyar.pixivdownload.core.hash.ImageHasher;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@PluginManagedBean
@RequiredArgsConstructor
public class DuplicateService {

    private static final int DEFAULT_THRESHOLD = 0;
    private static final int DEFAULT_AHASH_THRESHOLD = 0;
    private static final int MAX_THRESHOLD = 32;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final String SCOPE_CROSS_ARTWORK = "cross-artwork";
    private static final String SCOPE_ALL = "all";

    private final ImageHashMapper imageHashMapper;
    private final AppMessages messages;

    private volatile CacheEntry cache;

    public DuplicateDto.GroupsPage groups(Integer threshold,
                                          Integer aHashThreshold,
                                          String scope,
                                          int page,
                                          int size) {
        return groups(threshold, aHashThreshold, scope, page, size, DuplicateDto.idleScanStatus());
    }

    public DuplicateDto.GroupsPage groups(Integer threshold,
                                          Integer aHashThreshold,
                                          String scope,
                                          int page,
                                          int size,
                                          DuplicateDto.ScanStatus scanStatus) {
        Query query = normalize(threshold, aHashThreshold, scope, page, size);
        CacheFingerprint fingerprint = fingerprint();
        List<DuplicateDto.Group> allGroups = cachedGroups(query, fingerprint);
        int totalGroups = allGroups.size();
        int from = Math.min(query.page() * query.size(), totalGroups);
        int to = Math.min(from + query.size(), totalGroups);
        return new DuplicateDto.GroupsPage(
                query.page(),
                query.size(),
                totalGroups,
                allGroups.subList(from, to),
                scanStatus == null ? DuplicateDto.idleScanStatus() : scanStatus
        );
    }

    public void invalidate() {
        cache = null;
    }

    private List<DuplicateDto.Group> cachedGroups(Query query, CacheFingerprint fingerprint) {
        CacheEntry current = cache;
        CacheKey key = new CacheKey(query.threshold(), query.aHashThreshold(), query.scope(), fingerprint);
        if (current != null && current.key().equals(key)) {
            return current.groups();
        }
        synchronized (this) {
            current = cache;
            if (current != null && current.key().equals(key)) {
                return current.groups();
            }
            List<DuplicateDto.Group> groups = buildGroups(query);
            cache = new CacheEntry(key, groups);
            return groups;
        }
    }

    private List<DuplicateDto.Group> buildGroups(Query query) {
        List<ImageHashRow> rows = imageHashMapper.findAll();
        if (rows.size() < 2) {
            return List.of();
        }
        UnionFind unionFind = new UnionFind(rows.size());
        BkTree tree = new BkTree();
        for (int i = 0; i < rows.size(); i++) {
            ImageHashRow row = rows.get(i);
            for (int candidate : tree.query(row.dHash(), query.threshold())) {
                ImageHashRow other = rows.get(candidate);
                if (isAHashMatch(row, other, query.aHashThreshold())) {
                    unionFind.union(i, candidate);
                }
            }
            tree.add(row.dHash(), i);
        }

        Map<Integer, List<ImageHashRow>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            grouped.computeIfAbsent(unionFind.find(i), ignored -> new ArrayList<>()).add(rows.get(i));
        }

        List<DuplicateDto.Group> result = new ArrayList<>();
        for (List<ImageHashRow> groupRows : grouped.values()) {
            if (groupRows.size() < 2) {
                continue;
            }
            if (SCOPE_CROSS_ARTWORK.equals(query.scope()) && isSingleArtworkGroup(groupRows)) {
                continue;
            }
            groupRows.sort(Comparator.comparingLong(ImageHashRow::artworkId).thenComparingInt(ImageHashRow::page));
            int maxDistance = maxDHashDistance(groupRows);
            List<DuplicateDto.Item> items = groupRows.stream()
                    .map(this::toItem)
                    .toList();
            result.add(new DuplicateDto.Group(groupId(groupRows), groupRows.size(), maxDistance, items));
        }
        result.sort(Comparator
                .comparingInt(DuplicateDto.Group::size).reversed()
                .thenComparingInt(DuplicateDto.Group::maxDistance)
                .thenComparing(DuplicateDto.Group::groupId));
        return List.copyOf(result);
    }

    private DuplicateDto.Item toItem(ImageHashRow row) {
        long authorId = row.authorId() == null ? 0L : row.authorId();
        String authorName = row.authorName();
        if ((authorName == null || authorName.isBlank()) && row.authorId() != null) {
            authorName = String.valueOf(row.authorId());
        }
        if (authorName == null) {
            authorName = "";
        }
        String title = row.title() == null || row.title().isBlank()
                ? String.valueOf(row.artworkId())
                : row.title();
        return new DuplicateDto.Item(
                row.artworkId(),
                row.page(),
                title,
                authorId,
                authorName,
                row.xRestrict() == null ? 0 : row.xRestrict(),
                "/api/downloaded/thumbnail-file/" + row.artworkId() + "/" + row.page()
        );
    }

    private boolean isAHashMatch(ImageHashRow left, ImageHashRow right, int threshold) {
        if (left.aHash() == null || right.aHash() == null) {
            return true;
        }
        return ImageHasher.hamming(left.aHash(), right.aHash()) <= threshold;
    }

    private boolean isSingleArtworkGroup(List<ImageHashRow> rows) {
        long artworkId = rows.get(0).artworkId();
        for (ImageHashRow row : rows) {
            if (row.artworkId() != artworkId) {
                return false;
            }
        }
        return true;
    }

    private int maxDHashDistance(List<ImageHashRow> rows) {
        int max = 0;
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                max = Math.max(max, ImageHasher.hamming(rows.get(i).dHash(), rows.get(j).dHash()));
            }
        }
        return max;
    }

    private String groupId(List<ImageHashRow> rows) {
        ImageHashRow first = rows.get(0);
        return "g-" + first.artworkId() + "-p" + first.page();
    }

    private CacheFingerprint fingerprint() {
        return new CacheFingerprint(imageHashMapper.countAllHashRows(), imageHashMapper.maxCreatedTime());
    }

    private Query normalize(Integer threshold, Integer aHashThreshold, String scope, int page, int size) {
        int dThreshold = threshold == null ? DEFAULT_THRESHOLD : threshold;
        int aThreshold = aHashThreshold == null ? DEFAULT_AHASH_THRESHOLD : aHashThreshold;
        if (dThreshold < 0 || dThreshold > MAX_THRESHOLD) {
            throw new IllegalArgumentException(messages.get("duplicate.error.threshold.invalid", MAX_THRESHOLD));
        }
        if (aThreshold < 0 || aThreshold > MAX_THRESHOLD) {
            throw new IllegalArgumentException(messages.get("duplicate.error.ahash-threshold.invalid", MAX_THRESHOLD));
        }
        String normalizedScope = scope == null || scope.isBlank()
                ? SCOPE_CROSS_ARTWORK
                : scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_CROSS_ARTWORK.equals(normalizedScope) && !SCOPE_ALL.equals(normalizedScope)) {
            throw new IllegalArgumentException(messages.get("duplicate.error.scope.invalid"));
        }
        int normalizedPage = Math.max(0, page);
        int normalizedSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new Query(dThreshold, aThreshold, normalizedScope, normalizedPage, normalizedSize);
    }

    private record Query(int threshold, int aHashThreshold, String scope, int page, int size) {
    }

    private record CacheFingerprint(long rowCount, Long maxCreatedTime) {
    }

    private record CacheKey(int threshold, int aHashThreshold, String scope, CacheFingerprint fingerprint) {
    }

    private record CacheEntry(CacheKey key, List<DuplicateDto.Group> groups) {
        private CacheEntry {
            groups = List.copyOf(groups);
        }
    }

    private static final class UnionFind {
        private final int[] parent;
        private final int[] rank;

        private UnionFind(int size) {
            this.parent = new int[size];
            this.rank = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        private int find(int value) {
            int p = parent[value];
            if (p != value) {
                parent[value] = find(p);
            }
            return parent[value];
        }

        private void union(int left, int right) {
            int rootLeft = find(left);
            int rootRight = find(right);
            if (rootLeft == rootRight) {
                return;
            }
            if (rank[rootLeft] < rank[rootRight]) {
                parent[rootLeft] = rootRight;
            } else if (rank[rootLeft] > rank[rootRight]) {
                parent[rootRight] = rootLeft;
            } else {
                parent[rootRight] = rootLeft;
                rank[rootLeft]++;
            }
        }
    }

    private static final class BkTree {
        private Node root;

        private void add(long hash, int index) {
            if (root == null) {
                root = new Node(hash, index);
                return;
            }
            Node node = root;
            while (true) {
                int distance = ImageHasher.hamming(hash, node.hash);
                Node child = node.children.get(distance);
                if (child == null) {
                    node.children.put(distance, new Node(hash, index));
                    return;
                }
                node = child;
            }
        }

        private List<Integer> query(long hash, int threshold) {
            if (root == null) {
                return List.of();
            }
            List<Integer> result = new ArrayList<>();
            ArrayDeque<Node> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Node node = stack.pop();
                int distance = ImageHasher.hamming(hash, node.hash);
                if (distance <= threshold) {
                    result.add(node.index);
                }
                int min = Math.max(0, distance - threshold);
                int max = distance + threshold;
                for (Map.Entry<Integer, Node> entry : node.children.entrySet()) {
                    int edge = entry.getKey();
                    if (edge >= min && edge <= max) {
                        stack.push(entry.getValue());
                    }
                }
            }
            return result;
        }

        private static final class Node {
            private final long hash;
            private final int index;
            private final Map<Integer, Node> children = new HashMap<>();

            private Node(long hash, int index) {
                this.hash = hash;
                this.index = index;
            }
        }
    }
}
