package top.sywyar.pixivdownload.core.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 * 匹配时大小写不敏感且 {@code /} 与 {@code \} 等价，以兼容 Windows 用户混用的写法。
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
public class PathPrefixCodec {

    /** 符号根的固定 id：{@code {0}}，不入 {@code path_prefixes}。 */
    public static final long SYMBOLIC_ROOT_ID = 0L;
    /** 符号根的编码形态：{@code {0}}。 */
    public static final String SYMBOLIC_ROOT_TOKEN = "{0}";

    private static final Pattern ENCODED_PATTERN = Pattern.compile("^\\{(\\d+)}(?:[/\\\\](.*))?$");

    private final PathPrefixMapper mapper;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile List<PathPrefix> cachedPrefixes = List.of();
    /** 编码候选：DB 前缀 + （启用时）符号根，按路径长度降序、id 升序排序。 */
    private volatile List<PathPrefix> encodeCandidates = List.of();
    /** 符号根是否启用（root-folder 为相对路径）。启动时确定，运行期不变。 */
    private volatile boolean symbolicRootActive;
    /** 符号根的当前解析结果（绝对路径，去尾分隔符）。启动时解析一次并缓存。 */
    private volatile String symbolicRootPath;

    @PostConstruct
    public void init() {
        mapper.createTable();
        initSymbolicRoot();
        reload();
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
        lock.writeLock().lock();
        try {
            List<PathPrefix> all = mapper.findAll();
            cachedPrefixes = all == null ? List.of() : List.copyOf(all);
            List<PathPrefix> candidates = new ArrayList<>(cachedPrefixes);
            if (symbolicRootActive && symbolicRootPath != null && !symbolicRootPath.isEmpty()) {
                candidates.add(new PathPrefix(SYMBOLIC_ROOT_ID, symbolicRootPath));
            }
            // 与 findAll 的 ORDER BY LENGTH(path) DESC, id ASC 一致：符号根按长度参与最长匹配
            candidates.sort(Comparator.comparingInt((PathPrefix p) -> p.path().length()).reversed()
                    .thenComparingLong(PathPrefix::id));
            encodeCandidates = List.copyOf(candidates);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 注册或复用一个绝对路径前缀，返回对应 id。已存在的同名前缀直接返回原 id。
     * 符号根启用时，与符号根同路径的注册请求直接返回 {@link #SYMBOLIC_ROOT_ID}，不建行 ——
     * 避免 {@code path_prefixes} 中出现与 {@code {0}} 重复的前缀。
     */
    public long getOrCreatePrefixId(String absolutePath) {
        String stripped = stripTrailingSeparators(absolutePath);
        if (stripped == null || stripped.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (symbolicRootActive && normalizeForCompare(stripped).equals(normalizeForCompare(symbolicRootPath))) {
            return SYMBOLIC_ROOT_ID;
        }
        return forceCreatePrefixId(stripped);
    }

    /**
     * 与 {@link #getOrCreatePrefixId} 相同，但<b>不做</b>符号根同路径守卫：即使路径与当前符号根
     * 解析结果相同也照常建行。仅供「固定符号根」（pin，把 {@code {0}} 引用钉死为 {@code {N}}）使用 ——
     * 该场景恰恰需要为符号根的当前路径登记一条真实前缀行。
     */
    public long forceCreatePrefixId(String absolutePath) {
        String stripped = stripTrailingSeparators(absolutePath);
        if (stripped == null || stripped.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        mapper.insertIfAbsent(stripped);
        Long id = mapper.findIdByPath(stripped);
        if (id == null) {
            throw new IllegalStateException("path_prefixes lookup miss after insert: " + stripped);
        }
        reload();
        return id;
    }

    /**
     * 将绝对路径编码为 {@code {N}/relative}（若能匹配现有前缀），否则原样返回。
     * 已编码值（以 {@code {} 开头）会被原样返回，调用方可放心多次调用。
     */
    public String encode(String absolutePath) {
        if (absolutePath == null) return null;
        String stripped = stripTrailingSeparators(absolutePath);
        if (stripped.isEmpty()) return stripped;
        if (looksEncoded(stripped)) return stripped;

        String inputNorm = normalizeForCompare(stripped);
        for (PathPrefix prefix : currentEncodeCandidates()) {
            String prefixStripped = stripTrailingSeparators(prefix.path());
            if (prefixStripped == null || prefixStripped.isEmpty()) continue;
            String prefixNorm = normalizeForCompare(prefixStripped);
            if (!inputNorm.startsWith(prefixNorm)) continue;
            int prefixLen = prefixNorm.length();
            if (inputNorm.length() == prefixLen) {
                return "{" + prefix.id() + "}";
            }
            char boundary = inputNorm.charAt(prefixLen);
            if (boundary != '/' && boundary != '\\') continue;
            String rest = stripped.substring(prefixLen + 1);
            rest = rest.replace('\\', '/');
            return "{" + prefix.id() + "}/" + rest;
        }
        return stripped;
    }

    /**
     * 将 DB 中存储的值还原为绝对路径。无前缀引用的值原样返回。
     */
    public String resolve(String storedValue) {
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
            String rest0 = matcher.group(2);
            if (rest0 == null || rest0.isEmpty()) {
                return symbolicRootPath;
            }
            return symbolicRootPath + "/" + rest0;
        }
        String prefix = lookupPrefix(id);
        if (prefix == null) {
            log.warn(logMessage("download.db.log.prefix-id-not-found", id, storedValue));
            return storedValue;
        }
        String rest = matcher.group(2);
        if (rest == null || rest.isEmpty()) {
            return stripTrailingSeparators(prefix);
        }
        return stripTrailingSeparators(prefix) + "/" + rest;
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
        if (value == null) return null;
        return value.replaceAll("[/\\\\]+$", "");
    }

    private List<PathPrefix> currentPrefixes() {
        lock.readLock().lock();
        try {
            return cachedPrefixes;
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<PathPrefix> currentEncodeCandidates() {
        lock.readLock().lock();
        try {
            return encodeCandidates;
        } finally {
            lock.readLock().unlock();
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

    private static String normalizeForCompare(String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
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
