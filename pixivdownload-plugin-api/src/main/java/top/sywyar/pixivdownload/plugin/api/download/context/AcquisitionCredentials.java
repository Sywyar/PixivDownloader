package top.sywyar.pixivdownload.plugin.api.download.context;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** 一次采集调用的有序凭据副本；不会修改浏览器选择、凭据库或计划任务。 */
public final class AcquisitionCredentials {
    /** 一次调用允许的最大凭据候选数。 */
    public static final int MAX_CANDIDATES = 8;
    /** 单项凭据允许的最大字符数。 */
    public static final int MAX_SECRET_LENGTH = 16_384;
    private static final ScopedValue<AcquisitionCredentials> CURRENT = ScopedValue.newInstance();
    private final List<String> secrets;
    private final BooleanSupplier cancellation;

    /**
     * 创建有序凭据副本。
     * @param secrets 顺序固定的非空凭据，最多八项；不得用于日志或 DTO
     */
    public AcquisitionCredentials(List<String> secrets) {
        if (secrets == null || secrets.isEmpty() || secrets.size() > MAX_CANDIDATES)
            throw new IllegalArgumentException("Invalid acquisition credentials");
        this.cancellation = () -> false;
        this.secrets = secrets.stream().map(value -> {
            if (value == null || value.isBlank() || value.length() > MAX_SECRET_LENGTH
                    || value.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IllegalArgumentException("Invalid acquisition credentials");
            return value.strip();
        }).distinct().toList();
    }

    private AcquisitionCredentials(List<String> secrets, BooleanSupplier cancellation) {
        this.secrets = secrets;
        this.cancellation = Objects.requireNonNull(cancellation);
    }
    /**
     * 为同一候选副本叠加取消信号。
     * @param cancellation 本次调用的取消信号
     * @return 具有同一候选副本的调用作用域
     */
    public AcquisitionCredentials withCancellation(BooleanSupplier cancellation) {
        Objects.requireNonNull(cancellation);
        return new AcquisitionCredentials(secrets, () -> this.cancellation.getAsBoolean() || cancellation.getAsBoolean());
    }
    /** 在新请求前及交付结果前检查取消。 */
    public void checkCancelled() {
        if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean())
            throw new CancellationException("Acquisition cancelled");
    }
    /** {@return 固定的首选凭据；调用方不得输出或持久化到普通状态} */
    public String primary() { return secrets.getFirst(); }
    /** {@return 当前调用作用域；无多凭据时返回 null} */
    public static AcquisitionCredentials current() { return CURRENT.isBound() ? CURRENT.get() : null; }
    /** {@return 有序临时字符副本；调用方须清零每一项} */
    public List<char[]> copySecrets() { return secrets.stream().map(String::toCharArray).toList(); }
    /**
     * {@return 当前首选匹配时的候选列表；不同凭据不能借用环境中的其它账号}
     * @param primary 本次请求的首选凭据
     */
    public static List<String> candidates(String primary) {
        AcquisitionCredentials current = current();
        return current != null && Objects.equals(current.primary(), primary)
                ? current.secrets : List.of(primary == null ? "" : primary);
    }
    /**
     * 在当前凭据作用域内同步调用。
     * @param action 同步调用
     * @param <T> 返回类型
     * @param <X> 失败类型
     * @return 调用结果
     * @throws X 原调用失败
     */
    public <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> action) throws X {
        return ScopedValue.where(CURRENT, this).call(action);
    }
    /**
     * 在当前凭据作用域内同步执行。
     * @param action 同步调用
     */
    public void run(Runnable action) { ScopedValue.where(CURRENT, this).run(action); }
    /**
     * 捕获明确的异步任务，不向共享执行线程遗留上下文。
     * @param action 任务
     * @return 绑定当前作用域的任务
     */
    public static Runnable capture(Runnable action) {
        AcquisitionCredentials current = current();
        return current == null ? action : () -> current.run(action);
    }
    /**
     * 为异步任务捕获凭据作用域并叠加任务取消信号。
     * @param action 异步任务
     * @param cancelled 任务自身的取消信号
     * @return 合并取消信号的作用域任务
     */
    public static Runnable capture(Runnable action, BooleanSupplier cancelled) {
        AcquisitionCredentials current = current();
        return current == null ? action : () -> current.withCancellation(cancelled).run(action);
    }
    @Override public String toString() { return "AcquisitionCredentials[redacted]"; }
}
