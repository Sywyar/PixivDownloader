package top.sywyar.pixivdownload.core.db.pathprefix;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.function.LongFunction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路径前缀编解码器：将数据库中的绝对路径压缩为 {@code {N}/relative} 形式，
 * 读取时再透明还原为绝对路径，从而避免 {@code rootFolder} / 分类目标目录在
 * 多行中重复存储。
 *
 * <p>编码格式：{@code {id}/sub/path} 或 {@code {id}}（仅指向前缀本身）。
 * 始终使用 {@code /} 分隔编码部分；前缀路径本身保留写入时的原始分隔符，
 * 还原时由 {@link java.nio.file.Path} 自行处理 OS 差异。
 *
 * <p>路径匹配：按段对齐 —— 例如前缀 {@code D:\foo} 不会匹配 {@code D:\fooBar/x}。
 * Windows 盘符和 UNC 路径忽略大小写并接受两种分隔符，POSIX 路径保留大小写和反斜杠。
 *
 * <p><b>符号根 {@code {0}}：</b>当 {@code download.root-folder} 配置为<b>相对路径</b>时启用。
 * {@code {0}} 不入 {@code path_prefixes} 表，运行期固定解析为
 * {@code Path.of(rootFolder).toAbsolutePath().normalize()}，使整个软件目录被复制 / 搬迁后
 * 既有记录自动跟随新位置。root-folder 为绝对路径时 {@code {0}} 不参与编码（但仍可解码，
 * 兜底用户绕过迁移工具直接改配置的场景）。编码时 {@code {0}} 与普通前缀一样按路径长度参与最长匹配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathPrefixCodec implements StoredPathCodec {

    /** 符号根的固定 id：{@code {0}}，不入 {@code path_prefixes}。 */
    public static final long SYMBOLIC_ROOT_ID = 0L;
    /** 符号根的编码形态：{@code {0}}。 */
    public static final String SYMBOLIC_ROOT_TOKEN = "{0}";

    private static final Pattern ENCODED_PATTERN = Pattern.compile("\\{(\\d+)}(?:[/\\\\](.*))?", Pattern.DOTALL);
    private static final Pattern REDUNDANT_SEGMENTS = Pattern.compile("(?<=/)(?:\\.(?:/|\\z)|/+)");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^(?:[A-Za-z]:[/\\\\]|\\\\\\\\[^/\\\\]+[/\\\\][^/\\\\]+)");

    private final PathPrefixMapper mapper;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;

    private boolean databaseUnavailable;

    @org.springframework.beans.factory.annotation.Autowired
    public PathPrefixCodec(PathPrefixMapper mapper, DownloadConfig downloadConfig, AppMessages messages,
                           javax.sql.DataSource dataSource) {
        this(mapper, downloadConfig, messages);
        databaseUnavailable = top.sywyar.pixivdownload.core.db.UnavailableDataSource.isUnavailable(dataSource);
    }

    private final java.util.concurrent.locks.ReentrantReadWriteLock mappings = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private volatile long mappingRevision;

    /** Decode outside the mapping lock. If a migration committed meanwhile, reread before publishing. */
    public <T, R> R publishFromCurrentPaths(java.util.function.Supplier<T> read, java.util.function.Function<T, R> publish) {
        long revision = mappingRevision;
        T prepared = read.get();
        var lock = mappings.readLock();
        lock.lock();
        try {
            return publish.apply(revision == mappingRevision ? prepared : read.get());
        } finally { lock.unlock(); }
    }

    <T> T changeMappings(java.util.function.Supplier<T> transaction, java.util.function.Predicate<T> changed) {
        var lock = mappings.writeLock();
        lock.lock();
        try {
            T result = transaction.get();
            if (changed.test(result)) mappingRevision++;
            return result;
        } finally { lock.unlock(); }
    }

    private final Object cacheLock = new Object();
    private volatile PrefixSnapshot cached;
    /** 符号根是否启用（root-folder 为相对路径）。启动时确定，运行期不变。 */
    private volatile boolean symbolicRootActive;
    /** 符号根的当前解析结果（绝对路径，去尾分隔符）。启动时解析一次并缓存。 */
    private volatile String symbolicRootPath;

    @PostConstruct
    public void init() {
        if (databaseUnavailable) return;
        initSymbolicRoot();
    }

    private void initSymbolicRoot() {
        String rootFolder = downloadConfig.getRootFolder();
        Path root = Path.of(rootFolder);
        symbolicRootActive = !root.isAbsolute();
        symbolicRootPath = stripTrailingSeparators(root.toAbsolutePath().normalize().toString());
    }

    /** 符号根 {@code {0}} 是否启用（root-folder 为相对路径）。 */
    public boolean isSymbolicRootActive() {
        return symbolicRootActive;
    }

    /** 符号根 {@code {0}} 的当前解析结果（绝对路径，去尾分隔符）。无论是否启用都有值。 */
    public String getSymbolicRootPath() {
        return symbolicRootPath;
    }

    /**
     * 把符号根从运行期<b>编码</b>候选中摘除：仅供「pin（冻结）符号根」之后调用。
     *
     * <p>pin 已把数据库中全部 {@code {0}} 引用固定为真实的 {@code {N}} 前缀行，此后新记录不应再被编码为
     * {@code {0}}。否则在「{@code download.root-folder} 配置已改但服务尚未重启」的窗口里，新下载仍会按
     * <b>旧</b> root 编码成 {@code {0}}，重启后 {@code {0}} 解析到<b>新</b> root，导致这批记录指向错误目录。
     * 摘除后新下载改命中固定出来的 {@code {N}}（仍指向旧 root，与文件实际落点一致）。
     *
     * <p>仅影响编码：{@link #resolve} 仍保留 {@code {0}} → 旧 root 的解析能力以兜底任何残留引用。
     * 这是运行期变更，不写配置；重启后由 {@link #init()} 依据最新配置重新判定符号根是否启用。
     */
    public void deactivateSymbolicRootEncoding() {
        symbolicRootActive = false;
        reload();
    }

    /**
     * 重新加载前缀缓存。新增/更新前缀后调用即可让 {@link #encode} 立即看到最新数据。
     */
    public void reload() {
        var transaction = transactionPrefixes();
        if (transaction != null) {
            transaction.changed = true;
            transaction.snapshot = readSnapshot();
        } else {
            synchronized (cacheLock) { cached = readSnapshot(); }
        }
    }

    private PrefixSnapshot readSnapshot() {
        List<PathPrefix> all = mapper.findAll();
        List<PathPrefix> prefixes = all == null ? List.of() : List.copyOf(all);
        return new PrefixSnapshot(prefixes, encodingCandidates(prefixes, symbolicRootActive, symbolicRootPath));
    }

    private record EncodingPrefix(long id, String key, boolean windows) {}

    private static List<EncodingPrefix> encodingCandidates(List<PathPrefix> prefixes, boolean symbolic, String root) {
        var candidates = new ArrayList<>(prefixes);
        if (symbolic && root != null && !root.isEmpty()) candidates.add(new PathPrefix(SYMBOLIC_ROOT_ID, root));
        return candidates.stream().filter(p -> p.path() != null && !p.path().isEmpty())
                .map(p -> new EncodingPrefix(p.id(), comparisonKey(p.path()), windowsPath(p.path())))
                .sorted(Comparator.comparingInt((EncodingPrefix p) -> p.key().length()).reversed()
                        .thenComparingLong(EncodingPrefix::id)).toList();
    }

    /**
     * 注册或复用一个绝对路径前缀，返回对应 id。已存在的同名前缀直接返回原 id。
     * 符号根启用时，与符号根同路径的注册请求直接返回 {@link #SYMBOLIC_ROOT_ID}，不建行 ——
     * 避免 {@code path_prefixes} 中出现与 {@code {0}} 重复的前缀。
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public long getOrCreatePrefixId(String absolutePath) {
        var registration = registerPrefix(absolutePath, false, registrationIds());
        if (registration.id() != SYMBOLIC_ROOT_ID) reload();
        return registration.id();
    }

    /**
     * 与 {@link #getOrCreatePrefixId} 相同，但<b>不做</b>符号根同路径守卫：即使路径与当前符号根
     * 解析结果相同也照常建行。仅供「固定符号根」（pin，把 {@code {0}} 引用钉死为 {@code {N}}）使用 ——
     * 该场景恰恰需要为符号根的当前路径登记一条真实前缀行。
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public long forceCreatePrefixId(String absolutePath) {
        var registration = registerPrefix(absolutePath, true, registrationIds());
        reload();
        return registration.id();
    }

    record Registration(long id, boolean inserted) {}

    Map<String, Long> registrationIds() {
        var ids = new HashMap<String, Long>();
        for (var prefix : mapper.findAll()) ids.merge(comparisonKey(prefix.path()), prefix.id(), Long::min);
        return ids;
    }

    Registration registerPrefix(String absolutePath, boolean force, Map<String, Long> ids) {
        String path = stripTrailingSeparators(absolutePath);
        // 历史作品可能来自其他平台；磁盘上的目录检查由交互式迁移入口负责。
        if (path == null || !(path.startsWith("/") || WINDOWS_ABSOLUTE.matcher(path).lookingAt())) {
            throw new IllegalArgumentException("path prefix must be absolute");
        }
        if (!force && isSymbolicRootPath(path)) return new Registration(SYMBOLIC_ROOT_ID, false);
        String key = comparisonKey(path);
        Long existing = ids.get(key);
        if (existing != null) return new Registration(existing, false);
        int inserted = mapper.insertIfAbsent(path);
        Long id = mapper.findIdByPath(path);
        if (id == null) throw new org.springframework.dao.DataRetrievalFailureException("Missing prefix after insert");
        ids.put(key, id);
        return new Registration(id, inserted > 0);
    }

    /**
     * 将绝对路径编码为 {@code {N}/relative}（若能匹配现有前缀），否则原样返回。
     * 已编码值（以 {@code {} 开头）会被原样返回，调用方可放心多次调用。
     */
    public String encode(String absolutePath) {
        return encode(absolutePath, currentSnapshot().encoding());
    }

    private static String encode(String absolutePath, List<EncodingPrefix> candidates) {
        if (absolutePath == null) return null;
        String stripped = stripTrailingSeparators(absolutePath);
        if (stripped.isEmpty()) return stripped;
        if (ENCODED_PATTERN.matcher(stripped).matches()) return stripped;

        boolean windows = windowsPath(stripped);
        String path = comparisonPath(stripped, windows);
        String inputNorm = windows ? windowsKey(path) : path;
        for (EncodingPrefix prefix : candidates) {
            String prefixNorm = prefix.key();
            if (windows != prefix.windows() || !inputNorm.startsWith(prefixNorm)) continue;
            int prefixLen = prefixNorm.length();
            if (inputNorm.length() == prefixLen) {
                return "{" + prefix.id() + "}";
            }
            int boundary = prefixNorm.endsWith("/") ? prefixLen - 1 : prefixLen;
            if (inputNorm.charAt(boundary) != '/') continue;
            // A parent segment in the suffix can leave the prefix, including through a symbolic link.
            if (path.indexOf("/../", boundary) >= 0 || path.endsWith("/..")) return stripped;
            return "{" + prefix.id() + "}/" + path.substring(boundary + 1);
        }
        return stripped;
    }

    /**
     * 将 DB 中存储的值还原为绝对路径。无前缀引用的值原样返回。
     */
    public String resolve(String storedValue) {
        return resolve(storedValue, symbolicRootPath, id -> {
            String prefix = lookupPrefix(id);
            if (prefix == null) {
                log.warn(logMessage("download.db.log.prefix-id-not-found", id, storedValue));
            }
            return prefix;
        });
    }

    static String resolve(String storedValue, String symbolicRootPath, LongFunction<String> lookup) {
        if (storedValue == null) return null;
        Matcher matcher = ENCODED_PATTERN.matcher(storedValue);
        if (!matcher.matches()) {
            return storedValue;
        }
        long id;
        try {
            id = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return storedValue;
        }
        if (id == SYMBOLIC_ROOT_ID) {
            // 符号根不入表：固定解析为当前下载根目录的绝对路径
            return appendSuffix(symbolicRootPath, matcher.group(2));
        }
        String prefix = lookup.apply(id);
        if (prefix == null) {
            return storedValue;
        }
        return appendSuffix(stripTrailingSeparators(prefix), matcher.group(2));
    }

    private boolean isSymbolicRootPath(String path) {
        return symbolicRootActive && comparisonKey(path).equals(comparisonKey(symbolicRootPath));
    }

    private static String appendSuffix(String prefix, String rest) {
        if (rest == null || rest.isEmpty()) return prefix;
        return prefix + (prefix != null && (prefix.endsWith("/") || windowsPath(prefix) && prefix.endsWith("\\")) ? "" : "/") + rest;
    }

    /** 归档已包含来源的绝对符号根；匹配旧记录只需要来源路径的拼写规则。 */
    static java.util.function.UnaryOperator<String> archiveEncoder(Map<Long, String> prefixes) {
        var candidates = encodingCandidates(prefixes.entrySet().stream()
                .map(entry -> new PathPrefix(entry.getKey(), entry.getValue())).toList(), false, null);
        return path -> {
            String encoded = encode(path, candidates);
            if (encoded == null || encoded.isEmpty()) return encoded;
            Matcher match = ENCODED_PATTERN.matcher(encoded);
            if (!match.matches()) {
                if (java.io.File.separatorChar == '\\' && encoded.startsWith("/") && !windowsPath(encoded))
                    throw new java.nio.file.InvalidPathException("", "Unmapped POSIX absolute path");
                if (java.io.File.separatorChar == '/' && windowsPath(encoded))
                    throw new java.nio.file.InvalidPathException("", "Unmapped Windows absolute path");
                return encoded;
            }
            String suffix = match.group(2);
            if (suffix == null) return encoded;
            String prefix;
            try {
                prefix = prefixes.get(Long.parseLong(match.group(1)));
            } catch (NumberFormatException unknownId) {
                return encoded;
            }
            if (prefix == null) return encoded;
            if (windowsPath(prefix)) return encoded.replace('\\', '/');
            if (java.io.File.separatorChar == '\\') {
                for (String part : suffix.split("/")) {
                    if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
                    if (part.indexOf('\\') >= 0 || part.indexOf(':') >= 0 || part.endsWith(" ") || part.endsWith(".")
                            || WINDOWS_DEVICE.matcher(part).matches())
                        throw new java.nio.file.InvalidPathException("", "Unrepresentable POSIX path component");
                    Path.of(part); // Apply the target filesystem's remaining character restrictions.
                }
            }
            return encoded;
        };
    }

    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?", Pattern.DOTALL);

    /** 后端停止时，维护工具用当前数据库前缀和下载根建立只读编解码快照，不创建或修改前缀表。 */
    public static StoredPathCodec snapshot(String rootFolder, List<PathPrefix> prefixes) {
        Path root = Path.of(top.sywyar.pixivdownload.config.RuntimeFiles.normalizeRootFolder(rootFolder));
        String symbolicRoot = stripTrailingSeparators(root.toAbsolutePath().normalize().toString());
        var paths = new HashMap<Long, String>();
        prefixes.forEach(prefix -> paths.put(prefix.id(), prefix.path()));
        var encoding = encodingCandidates(prefixes, !root.isAbsolute(), symbolicRoot);
        return new StoredPathCodec() {
            @Override public String encode(String path) { return PathPrefixCodec.encode(path, encoding); }
            @Override public String resolve(String path) { return PathPrefixCodec.resolve(path, symbolicRoot, paths::get); }
        };
    }

    /**
     * 与 {@link #encode} 相同；但若没有任何前缀匹配，则把传入的绝对路径本身
     * 注册为一条新的 {@code path_prefixes} 行，并返回 {@code {newId}}。
     *
     * <p>用于 move 端点这类「写入时第一次看到的目录就是合法前缀」的入口：
     * Swing 客户端在分类工具设置里新增的 {@code target.folder.N} 不会即时通知
     * 服务端，但用户最终一定会把作品移动到该目录，到那时再注册即可。
     *
     * <p>对已编码值直接返回；空/空白值原样返回（不写表）。
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public String encodeOrRegister(String absolutePath) {
        if (absolutePath == null) return null;
        String stripped = stripTrailingSeparators(absolutePath);
        if (stripped.isEmpty()) return stripped;
        if (looksEncoded(stripped)) return stripped;
        String encoded = encode(stripped);
        if (!encoded.equals(stripped)) {
            return encoded;
        }
        long id = getOrCreatePrefixId(stripped);
        return "{" + id + "}";
    }

    /**
     * 是否已是 {@code {N}/...} 形式。供启动迁移判断幂等使用。
     */
    public boolean looksEncoded(String value) {
        if (value == null) return false;
        return ENCODED_PATTERN.matcher(value).matches();
    }

    public static String stripTrailingSeparators(String value) {
        if (value == null || value.isEmpty()) return value;
        int end = value.length();
        boolean windows = windowsPath(value);
        int rootLength = windows && value.charAt(1) == ':' ? 3 : 1;
        while (end > rootLength && (value.charAt(end - 1) == '/' || windows && value.charAt(end - 1) == '\\')) end--;
        return value.substring(0, end);
    }

    private List<PathPrefix> currentPrefixes() {
        return currentSnapshot().prefixes();
    }

    private PrefixSnapshot currentSnapshot() {
        if (databaseUnavailable) return new PrefixSnapshot(List.of(), List.of());
        var transaction = transactionPrefixes();
        if (transaction != null) {
            if (transaction.snapshot == null) transaction.snapshot = readSnapshot();
            return transaction.snapshot;
        }
        var current = cached;
        if (current != null) return current;
        synchronized (cacheLock) {
            if (cached == null) cached = readSnapshot();
            return cached;
        }
    }

    private TransactionPrefixes transactionPrefixes() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return null;
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof TransactionPrefixes prefixes && prefixes.owner() == this) return prefixes;
        }
        var prefixes = new TransactionPrefixes();
        TransactionSynchronizationManager.registerSynchronization(prefixes);
        return prefixes;
    }

    private record PrefixSnapshot(List<PathPrefix> prefixes, List<EncodingPrefix> encoding) {}

    /** 未提交的前缀只随当前事务存活，不发布给其它线程。 */
    private final class TransactionPrefixes implements TransactionSynchronization {
        private PrefixSnapshot snapshot = cached;
        private boolean changed;
        private PathPrefixCodec owner() { return PathPrefixCodec.this; }
        @Override public void savepointRollback(Object savepoint) { snapshot = null; }
        @Override public void afterCompletion(int status) {
            if (changed) synchronized (cacheLock) { cached = null; }
            snapshot = null;
        }
    }

    private String lookupPrefix(long id) {
        for (PathPrefix p : currentPrefixes()) {
            if (p.id() == id) return p.path();
        }
        // 缓存未命中（极罕见，例如启动期间外部并发写入），回源一次
        String fresh = mapper.findPathById(id);
        if (fresh != null) {
            reload();
        }
        return fresh;
    }

    private static boolean windowsPath(String path) {
        return path.startsWith("//") || WINDOWS_ABSOLUTE.matcher(path).lookingAt();
    }

    public static String comparisonKey(String path) {
        if (path == null) return null;
        boolean windows = windowsPath(path);
        String normalized = comparisonPath(path, windows);
        return windows ? windowsKey(normalized) : normalized;
    }

    private static String comparisonPath(String path, boolean windows) {
        String value = windows ? path.replace('\\', '/') : path;
        int start = value.startsWith("//") ? 2 : 0;
        if (!value.startsWith("//./") && !value.startsWith("//?/")
                && (value.contains("/.") || value.indexOf("//", start) >= 0)) {
            // Preserve the UNC anchor and parent segments, which may cross symbolic links.
            value = value.substring(0, start) + REDUNDANT_SEGMENTS.matcher(value.substring(start)).replaceAll("");
        }
        return stripTrailingSeparators(value);
    }

    private static String windowsKey(String normalized) {
        // WindowsPath compares UTF-16 characters without expanding case mappings.
        char[] key = normalized.toCharArray();
        for (int i = 0; i < key.length; i++) key[i] = Character.toUpperCase(key[i]);
        return new String(key);
    }

    /** Identify the mapping to adjust without putting private directory names in restore logs. */
    public static java.util.function.UnaryOperator<String> archivePrefixLabel(Map<Long, String> roots) {
        var encode = archiveEncoder(roots);
        return path -> {
            Matcher prefix = ENCODED_PATTERN.matcher(encode.apply(path));
            return prefix.matches() ? "{" + prefix.group(1) + "}" : "(absolute)";
        };
    }

    /** Expand a stored archive reference using its own prefix syntax, preserving component case. */
    public static String archiveSpelling(String stored, Map<Long, String> prefixes) {
        String absolute = resolve(stored, prefixes.get(0L), prefixes::get);
        return comparisonPath(absolute, windowsPath(absolute));
    }

    /**
     * 工具：返回缓存的前缀快照，供迁移脚本统计用。
     */
    public List<PathPrefix> snapshot() {
        return new ArrayList<>(currentPrefixes());
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
