package top.sywyar.pixivdownload.download.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.ArrayList;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathPrefixCodec {

    private static final Pattern ENCODED_PATTERN = Pattern.compile("^\\{(\\d+)}(?:[/\\\\](.*))?$");

    private final PathPrefixMapper mapper;
    private final AppMessages messages;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile List<PathPrefix> cachedPrefixes = List.of();

    @PostConstruct
    public void init() {
        mapper.createTable();
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
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 注册或复用一个绝对路径前缀，返回对应 id。已存在的同名前缀直接返回原 id。
     */
    public long getOrCreatePrefixId(String absolutePath) {
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
        for (PathPrefix prefix : currentPrefixes()) {
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
