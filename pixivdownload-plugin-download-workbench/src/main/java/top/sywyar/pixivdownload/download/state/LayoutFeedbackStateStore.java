package top.sywyar.pixivdownload.download.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackCommandRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 布局偏好调查服务端状态的专用存储：不可变快照 + revision / CAS + 单调状态转移 +
 * seen 合并 + 严格校验 + 原子写入 + 损坏恢复 + 健康状态。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>懒加载 + fail-open</b>：构造不访问文件系统；首次 {@link #snapshot()} /
 *       {@link #degraded()} / {@link #apply} 才加载（并发首次调用只加载一次）。文件不存在 /
 *       损坏 / 超大 / 不可读都不会令 Bean 构造失败；损坏文件被 best-effort 重命名为
 *       {@code *.corrupt-<timestamp>} 并隔离，I/O 不可用时 store 标记为 degraded；</li>
 *   <li><b>revision / CAS</b>：{@link #apply} 在锁内比较 expectedRevision，不一致不写文件、
 *       返回 {@link ApplyStatus#CONFLICT} 与当前快照；</li>
 *   <li><b>单调状态转移</b>：submitted &gt; never &gt; snoozed &gt; null，旧标签页的命令
 *       永远无法把 submitted / never 降级，record_seen 永不修改 state；</li>
 *   <li><b>原子写入</b>：临时文件 + {@code FileChannel.force(true)} + ATOMIC_MOVE
 *       （不支持时回退 REPLACE_EXISTING），move 成功后才更新内存快照；</li>
 *   <li><b>同一 JVM 内串行</b>：全部读写经 {@code synchronized} 锁。</li>
 * </ul>
 *
 * <p>日志不得输出文件内容、原始安装 UUID、scoped distinct ID 或用户建议。
 */
@Slf4j
public final class LayoutFeedbackStateStore {

    /** 持久化 schema 版本，固定为 1。 */
    public static final int SCHEMA_VERSION = 1;

    /** 状态文件大小上限（超出按损坏处理，不读取无限大内容）。 */
    public static final long MAX_STATE_FILE_BYTES = 64L * 1024;

    /** 三个稳定布局 ID。 */
    public static final Set<String> LAYOUT_IDS = Set.of(
            "pixiv-batch-landscape", "pixiv-batch-portrait", "pixiv-batch-alt");

    /** 稍后再说：7 天，由服务端按自己的当前时间计算。 */
    static final long SNOOZE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private static final String CORRUPT_SUFFIX_PREFIX = ".corrupt-";
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Path stateFile;
    private final Object lock = new Object();
    private volatile LayoutFeedbackStateSnapshot current = emptySnapshot();
    private volatile boolean degraded;
    /** 是否已执行首次文件加载（在 {@link #lock} 内判定 / 写入）。 */
    private boolean loaded;

    public LayoutFeedbackStateStore(LayoutFeedbackStateFiles files) {
        this(files.stateFile());
    }

    /**
     * 懒加载：构造只保存 {@code stateFile}，不访问文件系统、不创建目录、不隔离损坏文件。
     * 首次调用 {@link #snapshot()} / {@link #degraded()} / {@link #apply} 时才执行
     * {@link #ensureLoaded()}（并发首次调用在锁内只加载一次）。
     */
    LayoutFeedbackStateStore(Path stateFile) {
        this.stateFile = stateFile;
    }

    private void ensureLoaded() {
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loaded = true;
            loadInitialIntoState();
        }
    }

    /** 当前不可变快照（同一快照内 state 与 seen 一致）。 */
    public LayoutFeedbackStateSnapshot snapshot() {
        ensureLoaded();
        synchronized (lock) {
            return current;
        }
    }

    /** 状态存储是否可用；degraded 时 GET 仍可返回 scoped 身份，POST 返回 503。 */
    public boolean degraded() {
        ensureLoaded();
        return degraded;
    }

    /** 在锁内应用命令：CAS 一致则转移状态并原子持久化，否则返回 CONFLICT 与当前快照。 */
    public ApplyResult apply(LayoutFeedbackCommandRequest request, long now) throws IOException {
        ensureLoaded();
        synchronized (lock) {
            if (degraded) {
                throw new IllegalStateException("layout feedback state store is degraded");
            }
            LayoutFeedbackStateSnapshot snapshot = current;
            if (request.expectedRevision() != snapshot.revision()) {
                return new ApplyResult(ApplyStatus.CONFLICT, snapshot);
            }
            LayoutFeedbackStateSnapshot next = transition(snapshot, request, now);
            if (next.equals(snapshot)) {
                // no-op 命令：保持 revision，不落盘（行为固定，见测试）。
                return new ApplyResult(ApplyStatus.APPLIED, snapshot);
            }
            LayoutFeedbackStateDocument document = new LayoutFeedbackStateDocument(
                    SCHEMA_VERSION, next.revision(), next.state(), next.seen());
            byte[] bytes = STRICT_MAPPER.writeValueAsBytes(document);
            if (bytes.length > MAX_STATE_FILE_BYTES) {
                throw new IOException("layout feedback state document exceeds size limit");
            }
            persistAtomic(bytes, next);
            return new ApplyResult(ApplyStatus.APPLIED, next);
        }
    }

    /* ------------------------------------------------------------
       启动加载（fail-open，首次访问时才执行）
    ------------------------------------------------------------ */

    private void loadInitialIntoState() {
        try {
            if (!Files.exists(stateFile)) {
                current = emptySnapshot();
                return;
            }
            if (!Files.isRegularFile(stateFile)) {
                degraded = true;
                log.warn("Layout feedback state path is not a regular file; store degraded: {}",
                        stateFile.getFileName());
                current = emptySnapshot();
                return;
            }
            long size = Files.size(stateFile);
            if (size > MAX_STATE_FILE_BYTES) {
                quarantine("oversized");
                current = emptySnapshot();
                return;
            }
            byte[] bytes = Files.readAllBytes(stateFile);
            LayoutFeedbackStateDocument document = STRICT_MAPPER.readValue(bytes,
                    LayoutFeedbackStateDocument.class);
            current = document.toSnapshot();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // JSON 损坏 / schema 非法 / 字段错误：隔离损坏文件，store 继续可用。
            quarantine("corrupt");
            current = emptySnapshot();
        } catch (IOException e) {
            degraded = true;
            log.warn("Layout feedback state store degraded ({}): {}",
                    stateFile.getFileName(), e.getMessage());
            current = emptySnapshot();
        } catch (RuntimeException e) {
            // 记录构造校验等运行时失败同样按损坏隔离。
            quarantine("corrupt");
            current = emptySnapshot();
        }
    }

    private void quarantine(String reason) {
        try {
            Path target = stateFile.resolveSibling(stateFile.getFileName() + CORRUPT_SUFFIX_PREFIX
                    + System.currentTimeMillis());
            Files.move(stateFile, target, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Layout feedback state file quarantined as {} ({})",
                    target.getFileName(), reason);
        } catch (IOException e) {
            log.warn("Layout feedback state file is corrupt ({}); quarantine rename failed: {}",
                    reason, e.getMessage());
        }
    }

    private static LayoutFeedbackStateSnapshot emptySnapshot() {
        return new LayoutFeedbackStateSnapshot(0L, null, Map.of());
    }

    /* ------------------------------------------------------------
       时间安全函数（服务端墙钟可能回拨 / 时钟源可能返回负值）
    ------------------------------------------------------------ */

    /** 负时间防御：墙钟回拨前 / 时钟源异常时返回 0。 */
    private static long nonNegativeNow(long now) {
        return Math.max(0L, now);
    }

    /**
     * 饱和加法：溢出时返回 {@link Long#MAX_VALUE}，结果不允许为负数。
     * 防止 snooze 时间加法在接近上限时溢出为负。
     */
    private static long saturatingAdd(long base, long delta) {
        if (base > 0 && delta > 0 && delta > Long.MAX_VALUE - base) {
            return Long.MAX_VALUE;
        }
        long result = base + delta;
        return result < 0 ? 0L : result;
    }

    /* ------------------------------------------------------------
       状态转移（单调）
    ------------------------------------------------------------ */

    private static LayoutFeedbackStateSnapshot transition(
            LayoutFeedbackStateSnapshot snapshot, LayoutFeedbackCommandRequest request, long now) {
        switch (request.type()) {
            case RECORD_SEEN:
                return mergeSeen(snapshot, request.layoutIds(), now);
            case SNOOZE:
                return applyDecision(snapshot, request.surveyId(), LayoutFeedbackDecision.SNOOZED, now);
            case NEVER:
                return applyDecision(snapshot, request.surveyId(), LayoutFeedbackDecision.NEVER, now);
            case SUBMITTED:
                return applyDecision(snapshot, request.surveyId(), LayoutFeedbackDecision.SUBMITTED, now);
            default:
                throw new IllegalStateException("unreachable command type");
        }
    }

    /**
     * 单调决策转移：submitted &gt; never &gt; snoozed &gt; null。
     * 返回与旧快照相同对象表示 no-op（revision 不变）。
     *
     * <p>服务端墙钟回拨防御：
     * - 同一 Survey 已有 SNOOZED 时，safeNow = max(nonNegativeNow(now), old.updatedAt)，
     *   proposedUntil = saturatingAdd(nonNegativeNow(now), SNOOZE_MILLIS)，
     *   nextUntil = max(old.snoozedUntil(), proposedUntil)，重复 snooze 绝不缩短已有
     *   snooze；nextUntil / nextUpdatedAt 都与旧值相同则 no-op（revision 不变、不落盘）；
     * - 状态升级（snoozed → never / submitted，never → submitted）的新 updatedAt =
     *   max(old.updatedAt(), nonNegativeNow(now))，不得倒退；
     * - 新 Survey ID 使用 nonNegativeNow(now)；
     * - submitted / never 重复操作仍是幂等 no-op。
     */
    private static LayoutFeedbackStateSnapshot applyDecision(
            LayoutFeedbackStateSnapshot snapshot, String surveyId,
            LayoutFeedbackDecision incoming, long now) {
        long safeNow = nonNegativeNow(now);
        LayoutFeedbackStateEntry oldState = snapshot.state();
        if (oldState != null && !oldState.surveyId().equals(surveyId)) {
            // 新 Survey ID：新状态命令直接替换旧 Survey state，seen 保留。
            oldState = null;
        }
        if (oldState != null) {
            int currentRank = rank(oldState.status());
            int incomingRank = rank(incoming);
            if (incomingRank < currentRank) {
                // 不得降级（submitted 不被 never / snooze 降级，never 不被 snooze 降级）。
                return snapshot;
            }
            if (incomingRank == currentRank) {
                if (incoming == LayoutFeedbackDecision.SNOOZED) {
                    // 重复 snooze：以 max(now, old.updatedAt) 为安全基准，proposedUntil
                    // 使用饱和加法；nextUntil 取 max(旧值, proposedUntil)，绝不缩短；
                    // 两者都无变化时 no-op（revision 不变、不落盘）。
                    long safeBase = Math.max(safeNow, oldState.updatedAt());
                    long proposedUntil = saturatingAdd(safeNow, SNOOZE_MILLIS);
                    long nextUntil = Math.max(oldState.snoozedUntil(), proposedUntil);
                    long nextUpdatedAt = Math.max(oldState.updatedAt(), safeNow);
                    if (nextUntil == oldState.snoozedUntil() && nextUpdatedAt == oldState.updatedAt()) {
                        return snapshot;
                    }
                    return replaceState(snapshot, new LayoutFeedbackStateEntry(
                            surveyId, incoming, nextUpdatedAt, nextUntil));
                }
                // 重复 submitted / never：幂等 no-op。
                return snapshot;
            }
            // 状态升级：updatedAt 不得倒退。
            safeNow = Math.max(safeNow, oldState.updatedAt());
        }
        long snoozedUntil = incoming == LayoutFeedbackDecision.SNOOZED
                ? saturatingAdd(safeNow, SNOOZE_MILLIS)
                : 0L;
        return replaceState(snapshot, new LayoutFeedbackStateEntry(
                surveyId, incoming, safeNow, snoozedUntil));
    }

    /**
     * record_seen：永不修改 state；firstSeenAt 旧值存在时保持旧值，lastSeenAt =
     * max(旧值, nonNegativeNow(now))——服务端墙钟回拨时 lastSeenAt 绝不倒退；
     * 无变化时 no-op（revision 不变、不落盘）。返回与旧快照相同对象表示无变化。
     */
    private static LayoutFeedbackStateSnapshot mergeSeen(
            LayoutFeedbackStateSnapshot snapshot, List<String> layoutIds, long now) {
        boolean changed = false;
        long safeNow = nonNegativeNow(now);
        Map<String, LayoutFeedbackSeenEntry> seen = new LinkedHashMap<>(snapshot.seen());
        for (String layoutId : layoutIds) {
            LayoutFeedbackSeenEntry old = seen.get(layoutId);
            LayoutFeedbackSeenEntry merged;
            if (old == null) {
                // 新 entry：firstSeenAt = lastSeenAt = safeNow，绝不构造 lastSeenAt < firstSeenAt。
                merged = new LayoutFeedbackSeenEntry(safeNow, safeNow);
            } else {
                long nextLastSeenAt = Math.max(old.lastSeenAt(), safeNow);
                if (nextLastSeenAt == old.lastSeenAt()) {
                    continue;
                }
                merged = new LayoutFeedbackSeenEntry(old.firstSeenAt(), nextLastSeenAt);
            }
            seen.put(layoutId, merged);
            changed = true;
        }
        if (!changed) {
            return snapshot;
        }
        return new LayoutFeedbackStateSnapshot(snapshot.revision() + 1, snapshot.state(), seen);
    }

    private static LayoutFeedbackStateSnapshot replaceState(
            LayoutFeedbackStateSnapshot snapshot, LayoutFeedbackStateEntry state) {
        return new LayoutFeedbackStateSnapshot(
                snapshot.revision() + 1, state, snapshot.seen());
    }

    private static int rank(LayoutFeedbackDecision decision) {
        switch (decision) {
            case SUBMITTED:
                return 3;
            case NEVER:
                return 2;
            case SNOOZED:
                return 1;
            default:
                return 0;
        }
    }

    /* ------------------------------------------------------------
       原子持久化
    ------------------------------------------------------------ */

    private void persistAtomic(byte[] bytes, LayoutFeedbackStateSnapshot next) throws IOException {
        Path dir = stateFile.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, stateFile.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            current = next;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanupFailure) {
                    log.warn("Failed to clean up layout feedback state temp file: {}",
                            cleanupFailure.getMessage());
                }
            }
        }
    }

    /* ------------------------------------------------------------
       结果类型
    ------------------------------------------------------------ */

    public enum ApplyStatus {
        /** 命令已应用（或幂等 no-op），响应带新快照。 */
        APPLIED,
        /** expectedRevision 与当前 revision 不一致，未写文件，响应带当前快照。 */
        CONFLICT
    }

    public record ApplyResult(ApplyStatus status, LayoutFeedbackStateSnapshot snapshot) {
    }
}
