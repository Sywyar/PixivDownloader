package top.sywyar.pixivdownload.plugin.web.page;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePublications;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 不可变快照先落盘，唯一 current 提交记录决定页面、安全要求和权限的共同修订。 */
@Component
public final class WebPublicationStore {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Requirement(String operation, String contextSchema, String policy, String provider,
            String packageId, OperationVerification.Policy plan) { }
    public record HookRequirement(top.sywyar.pixivdownload.plugin.api.operation.OperationBinding binding,
            String operation, String inputSchema, String resultSchema, String provider, String packageId,
            top.sywyar.pixivdownload.plugin.api.operation.OperationHook.Definition definition, String targetContract, Set<HookTarget> targetAnchors) {
        public HookRequirement {
            targetAnchors = Set.copyOf(targetAnchors);
            if (targetAnchors.size() > 8192 || targetAnchors.stream().mapToInt(HookTarget::entries).sum() > 8192)
                throw new IllegalArgumentException("PAGE_HOOK_CAPACITY");
        }
        public HookRequirement(top.sywyar.pixivdownload.plugin.api.operation.OperationBinding binding,
                String operation, String inputSchema, String resultSchema, String provider, String packageId,
                top.sywyar.pixivdownload.plugin.api.operation.OperationHook.Definition definition, String targetContract) {
            this(binding, operation, inputSchema, resultSchema, provider, packageId, definition, targetContract, Set.of());
        }
        public boolean matches(String operation, String endpoint, Map<String, List<String>> groups) {
            return binding.endpoint().isEmpty() ? this.operation.equals(operation) || groups.getOrDefault(this.operation, List.of()).contains(endpoint)
                    : binding.endpoint().equals(endpoint);
        }
    }
    /** 只持有原批准映射的纯值；实际请求条件仍交给 MVC 判定。 */
    public record HookTarget(String owner, String handler, Set<String> routes, List<String> parameters, List<String> headers,
            List<String> consumes, List<String> produces, boolean bodyRequired, Set<String> paths, Set<String> methods, Set<String> alternatives) {
        public HookTarget {
            routes = Set.copyOf(routes); parameters = List.copyOf(parameters); headers = List.copyOf(headers);
            consumes = List.copyOf(consumes); produces = List.copyOf(produces);
            paths = Set.copyOf(paths); methods = Set.copyOf(methods); alternatives = Set.copyOf(alternatives);
            if (owner.length() > 128 || handler.length() > 512 || routes.size() > 64
                    || routes.stream().anyMatch(value -> value.length() > 1100)
                    || paths.size() > 64 || paths.stream().anyMatch(value -> value.length() > 1024)
                    || methods.size() > 8 || methods.stream().anyMatch(value -> !value.matches("GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE"))
                    || alternatives.size() > 4096 || alternatives.stream().anyMatch(value -> !value.matches("[a-f0-9]{64}"))
                    || java.util.stream.Stream.of(parameters, headers, consumes, produces).flatMap(Collection::stream)
                            .mapToLong(String::length).sum() > 16384) throw new IllegalArgumentException("PAGE_HOOK_CAPACITY");
            routes = routes.stream().map(HookTarget::routeShape).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        public int entries() { return 1 + routes.size() + paths.size() + methods.size() + alternatives.size(); }
        public HookTarget withAlternatives(Set<String> values) {
            return new HookTarget(owner, handler, routes, parameters, headers, consumes, produces, bodyRequired, paths, methods, values);
        }
        public boolean relatedTo(String currentOwner, String currentHandler, String method, String route) {
            // 同名方法属于原 owner；同一路由被其它 owner 接管时仍须重新批准。
            return owner.equals(currentOwner) && handler.equals(currentHandler) && routes.stream().anyMatch(value -> value.startsWith(method + ":"))
                    || routes.contains(routeShape(method + ":" + route));
        }
        private static String routeShape(String anchor) {
            if (anchor.indexOf('{') < 0) return anchor;
            // 变量名不改变路径范围；正则中的嵌套量词和转义必须原样保留。
            var result = new StringBuilder(anchor.length());
            int depth = 0;
            for (int index = 0; index < anchor.length(); index++) {
                char ch = anchor.charAt(index);
                result.append(ch);
                if (ch == '\\' && depth > 0 && index + 1 < anchor.length()) {
                    result.append(anchor.charAt(++index));
                } else if (ch == '{') {
                    depth++;
                    if (depth == 1) {
                        if (index + 1 < anchor.length() && anchor.charAt(index + 1) == '*') result.append(anchor.charAt(++index));
                        while (index + 1 < anchor.length() && anchor.charAt(index + 1) != ':' && anchor.charAt(index + 1) != '}') index++;
                    }
                } else if (ch == '}' && depth > 0) depth--;
            }
            return result.toString();
        }
    }
    public record Page(String document, String projection, String previousDocument,
            List<Requirement> requirements, List<PagePublications.Permission> permissions, List<HookRequirement> hooks) {
        public Page {
            requirements = List.copyOf(requirements); permissions = List.copyOf(permissions);
            hooks = List.copyOf(hooks);
        }
        public Page(String document, String projection, String previousDocument,
                List<Requirement> requirements, List<PagePublications.Permission> permissions) {
            this(document, projection, previousDocument, requirements, permissions, List.of());
        }
    }
    public record HookSettings(Map<String, List<String>> groups, List<top.sywyar.pixivdownload.plugin.api.operation.OperationBinding> bindings) {
        public HookSettings {
            var copy = new TreeMap<String, List<String>>(); groups.forEach((id, endpoints) -> copy.put(id, List.copyOf(endpoints)));
            groups = Map.copyOf(copy); bindings = List.copyOf(bindings);
        }
    }
    public record HookState(HookSettings settings, List<HookRequirement> requirements, List<PagePublications.Permission> permissions) {
        public HookState { requirements = List.copyOf(requirements); permissions = List.copyOf(permissions); }
        public static HookState empty() { return new HookState(new HookSettings(Map.of(), List.of()), List.of(), List.of()); }
    }
    public record Snapshot(long revision, String epoch, Map<String, Page> pages, HookState configuration) {
        public Snapshot { pages = Map.copyOf(pages); UUID.fromString(epoch); Objects.requireNonNull(configuration); }
        public Snapshot(long revision, String epoch, Map<String, Page> pages) { this(revision, epoch, pages, HookState.empty()); }
        public String identity() { return epoch + ":" + revision; }
        public List<Requirement> requirements(String operation) {
            return pages.values().stream().flatMap(page -> page.requirements().stream())
                    .filter(item -> item.operation().equals(operation)).distinct().toList();
        }
        public List<HookRequirement> hooks(String operation, String endpoint) {
            return hooks()
                    .filter(item -> item.matches(operation, endpoint, configuration.settings().groups()))
                    .distinct().sorted(Comparator.comparing((HookRequirement item) -> item.binding().phase())
                            .thenComparingInt(item -> item.binding().order()).thenComparing(item -> item.binding().id())).toList();
        }
        public java.util.stream.Stream<HookRequirement> hooks() {
            return java.util.stream.Stream.concat(pages.values().stream().flatMap(page -> page.hooks().stream()), configuration.requirements().stream());
        }
    }
    private record Commit(int format, long revision, String file, String digest) { }
    public record RecoveryCandidate(String file, String sha256, long revision, Set<String> pages,
            Map<String, List<String>> operations, List<PagePublications.Permission> permissions) { }
    private final Path directory;
    private final Path enrollment;
    private Snapshot cached;
    private Commit cachedCommit;
    private boolean unavailable;
    private final java.util.function.Consumer<String> probe;

    public WebPublicationStore() { this(RuntimeFiles.webPublicationDirectory(), RuntimeFiles.webPublicationEnrollment()); }
    public WebPublicationStore(Path directory, Path enrollment) {
        this(directory, enrollment, ignored -> { });
    }
    WebPublicationStore(Path directory, Path enrollment, java.util.function.Consumer<String> probe) {
        this(directory, enrollment, probe, true);
    }

    private WebPublicationStore(Path directory, Path enrollment, java.util.function.Consumer<String> probe, boolean initialize) {
        this.directory = directory.toAbsolutePath().normalize();
        this.enrollment = enrollment.toAbsolutePath().normalize();
        this.probe = probe;
        try { if (initialize) { initialize(); snapshot(); } }
        catch (IOException | RuntimeException failure) {
            reportUnavailable("INITIALIZE", -1, failure);
        }
    }

    /** CLI 已取得进程单实例锁；查看状态绝不建立空发布。 */
    public static WebPublicationStore openForRecovery() {
        return openForRecovery(RuntimeFiles.webPublicationDirectory(), RuntimeFiles.webPublicationEnrollment());
    }

    static WebPublicationStore openForRecovery(Path directory, Path enrollment) {
        return new WebPublicationStore(directory, enrollment, ignored -> { }, false);
    }

    public synchronized List<RecoveryCandidate> recoveryCandidates() throws IOException {
        PlainFilePathGuard.requirePlainParent(directory.resolve("current.json"), false);
        var candidates = new ArrayList<RecoveryCandidate>();
        try (var files = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path file : files) {
                if (++count > 256) throw new IOException("publication directory budget");
                String name = file.getFileName().toString();
                if (!name.matches("[a-f0-9-]{36}\\.json")) continue;
                byte[] bytes = read(file, MAX_BYTES);
                Snapshot value;
                try { value = parseSnapshot(bytes); }
                catch (IOException | RuntimeException invalid) { continue; }
                var operations = new TreeMap<String, List<String>>();
                value.pages().values().stream().flatMap(page -> page.requirements().stream()).forEach(requirement ->
                        operations.computeIfAbsent(requirement.operation(), ignored -> new ArrayList<>()).add(requirement.policy()));
                java.util.stream.Stream.concat(value.pages().values().stream().flatMap(page -> page.hooks().stream()), value.configuration().requirements().stream()).forEach(requirement ->
                        operations.computeIfAbsent(requirement.operation(), ignored -> new ArrayList<>())
                                .add(requirement.binding().hook() + "@" + requirement.binding().phase()));
                operations.replaceAll((operation, policies) -> policies.stream().distinct().sorted().toList());
                candidates.add(new RecoveryCandidate(name, hash(bytes), value.revision(), value.pages().keySet(), operations,
                        java.util.stream.Stream.concat(value.pages().values().stream().flatMap(page -> page.permissions().stream()),
                                value.configuration().permissions().stream()).distinct().toList()));
            }
        }
        return candidates.stream().sorted(Comparator.comparingLong(RecoveryCandidate::revision).reversed()).toList();
    }

    /** 候选可能是未提交快照，只有本机管理员的精确摘要选择才允许使其成为正式状态。 */
    public synchronized long recover(String file, String expectedHash) throws IOException {
        if (file == null || !file.matches("[a-f0-9-]{36}\\.json")
                || expectedHash == null || !expectedHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("PAGE_RECOVERY_SELECTION_INVALID");
        Path lock = directory.resolve("commit.lock");
        PlainFilePathGuard.requirePlainParent(lock, false);
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(lock);
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lease = channel.tryLock()) {
            if (lease == null) throw new IllegalStateException("PAGE_REVISION_CONFLICT");
            if (recoveryCandidates().size() >= 128) throw new IOException("publication recovery budget");
            PlainFilePathGuard.requirePlainParent(enrollment, true);
            if (Files.exists(enrollment, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(enrollment);
            byte[] bytes = read(directory.resolve(file), MAX_BYTES);
            if (!hash(bytes).equals(expectedHash)) throw new IllegalStateException("PAGE_RECOVERY_DIGEST_CHANGED");
            Snapshot selected = parseSnapshot(bytes);
            // 独立恢复代际防止旧文档与新发布重复使用相同计数修订。
            write(new Snapshot(0, UUID.randomUUID().toString(), selected.pages(), selected.configuration()));
            Path marker = enrollment.resolveSibling(".enrollment-" + UUID.randomUUID() + ".tmp");
            try {
                forceNew(marker, "1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (Files.exists(enrollment, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(enrollment);
                Files.move(marker, enrollment, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                forceDirectory(enrollment.getParent());
            } finally { Files.deleteIfExists(marker); }
            cached = null; cachedCommit = null;
            long revision = snapshot().revision();
            org.slf4j.LoggerFactory.getLogger(getClass()).info(top.sywyar.pixivdownload.i18n.MessageBundles.getForLog("web-publication.log.recovered", snapshot().identity(), expectedHash));
            return revision;
        }
    }

    private static Snapshot parseSnapshot(byte[] bytes) throws IOException {
        Snapshot value = JSON.readValue(bytes, Snapshot.class);
        if (value.revision() < 0 || value.pages().size() > 128) throw new IOException("publication snapshot invalid");
        return value;
    }

    private void initialize() throws IOException {
        if (Files.exists(enrollment, LinkOption.NOFOLLOW_LINKS)) return;
        // 登记先于初始提交；中断留下未知状态，不能在下次启动偷偷清空。
        PlainFilePathGuard.requirePlainParent(enrollment, true);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("publication enrollment missing");
        forceNew(enrollment, "1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PlainFilePathGuard.requirePlainParent(directory.resolve("current.json"), true);
        write(new Snapshot(0, UUID.randomUUID().toString(), Map.of()));
    }

    public synchronized Snapshot snapshot() {
        String stage = "ENROLLMENT";
        long revision = -1;
        try {
            PlainFilePathGuard.requirePlainRegularFile(enrollment);
            if (!Arrays.equals(read(enrollment, 8), "1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                throw new IOException("publication enrollment invalid");
            stage = "COMMIT";
            Commit commit = JSON.readValue(read(directory.resolve("current.json"), 1024), Commit.class);
            if (commit.format() != 1 || commit.revision() < 0 || !commit.file().matches("[a-f0-9-]{36}\\.json")
                    || !commit.digest().matches("[a-f0-9]{64}")) throw new IOException("publication commit invalid");
            revision = commit.revision();
            stage = "DIGEST";
            Path file = directory.resolve(commit.file());
            // 即使快照已缓存，仍校验磁盘字节；缺失或损坏不能保留可放行的旧状态。
            if (!hashFile(file).equals(commit.digest())) throw new IOException("publication digest mismatch");
            if (!commit.equals(cachedCommit)) {
                byte[] bytes = read(file, MAX_BYTES);
                if (!hash(bytes).equals(commit.digest())) throw new IOException("publication changed during read");
                stage = "SNAPSHOT";
                Snapshot snapshot = parseSnapshot(bytes);
                if (snapshot.revision() != commit.revision())
                    throw new IOException("publication snapshot invalid");
                cached = snapshot; cachedCommit = commit;
            }
            if (unavailable) {
                unavailable = false;
                try {
                    org.slf4j.LoggerFactory.getLogger(getClass()).info(top.sywyar.pixivdownload.i18n.MessageBundles.getForLog(
                            "web-publication.log.available", cached.identity()));
                } catch (RuntimeException ignored) { /* 日志故障不改变已验证的发布状态。 */ }
            }
            return cached;
        } catch (IOException | RuntimeException failure) {
            cached = null; cachedCommit = null;
            reportUnavailable(stage, revision, failure);
            throw new IllegalStateException("PAGE_PUBLICATION_UNAVAILABLE", failure);
        }
    }

    private void reportUnavailable(String stage, long revision, Exception failure) {
        // 同一次不可用区间只记一次，恢复后重新允许记录；不保留可能包含页面内容的异常。
        if (unavailable) return;
        unavailable = true;
        try {
            org.slf4j.LoggerFactory.getLogger(getClass()).error(top.sywyar.pixivdownload.i18n.MessageBundles.getForLog(
                    "web-publication.log.unavailable", stage, Long.toString(revision), failure.getClass().getSimpleName()));
        } catch (RuntimeException ignored) { /* 保留原始失败与拒绝语义。 */ }
    }

    /** CAS 在跨进程文件锁内复核；激活失败也只能恢复已提交修订。 */
    public synchronized void commit(long expected, Snapshot next) {
        try {
            Path lock = directory.resolve("commit.lock");
            PlainFilePathGuard.requirePlainParent(lock, false);
            if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(lock);
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lease = channel.tryLock()) {
                if (lease == null) throw new IllegalStateException("PAGE_REVISION_CONFLICT");
                if (snapshot().revision() != expected || !snapshot().epoch().equals(next.epoch())
                        || next.revision() != Math.addExact(expected, 1))
                    throw new IllegalStateException("PAGE_REVISION_CONFLICT");
                prune(cachedCommit.file());
                write(next);
                probe.accept("activate");
                snapshot();
            }
        } catch (IOException failure) { throw new IllegalStateException("PAGE_STORAGE_FAILED", failure); }
    }

    /** 当前提交在整个清理与写入期间保留；只删除本存储生成的旧快照及中断临时文件。 */
    private void prune(String current) throws IOException {
        try (var files = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path file : files) {
                if (++count > 256) throw new IOException("publication directory budget");
                String name = file.getFileName().toString();
                if (!name.equals(current) && (name.matches("[a-f0-9-]{36}\\.json")
                        || name.matches("\\.commit-[a-f0-9-]{36}\\.tmp"))) {
                    PlainFilePathGuard.requirePlainRegularFile(file);
                    Files.delete(file);
                }
            }
        }
    }

    private void write(Snapshot next) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(next);
        if (bytes.length > MAX_BYTES || next.pages().size() > 128) throw new IOException("publication budget");
        String file = UUID.randomUUID() + ".json";
        probe.accept("prepare");
        forceNew(directory.resolve(file), bytes);
        probe.accept("snapshot");
        replaceCommit(new Commit(1, next.revision(), file, hash(bytes)));
    }

    private void replaceCommit(Commit value) throws IOException {
        Path current = directory.resolve("current.json");
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) PlainFilePathGuard.requirePlainRegularFile(current);
        byte[] commit = JSON.writeValueAsBytes(value);
        Path temporary = directory.resolve(".commit-" + UUID.randomUUID() + ".tmp");
        try {
            forceNew(temporary, commit);
            probe.accept("replace");
            Files.move(temporary, directory.resolve("current.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(directory);
            probe.accept("committed");
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void forceNew(Path file, byte[] bytes) throws IOException {
        PlainFilePathGuard.requirePlainParent(file, false);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        forceDirectory(file.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Windows 不提供 JDK 目录 fsync，耐久边界由文件 force 与原子替换组成。
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return;
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static byte[] read(Path file, int limit) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("publication budget");
            return bytes;
        }
    }
    private static String hashFile(Path file) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(file);
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192]; int total = 0, size;
            while ((size = input.read(buffer)) >= 0) {
                total += size;
                if (total > MAX_BYTES) throw new IOException("publication budget");
                digest.update(buffer, 0, size);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String hash(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
