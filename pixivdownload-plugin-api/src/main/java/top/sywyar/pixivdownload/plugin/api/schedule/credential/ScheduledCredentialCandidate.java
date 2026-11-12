package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import java.util.Arrays;

/** 有序候选的独立临时副本；关闭时清零，调用方不得缓存或转交。 */
public final class ScheduledCredentialCandidate implements AutoCloseable {
    private final String accountKey;
    private char[] secret;
    /**
     * 创建计划任务凭据的独立副本。
     * @param accountKey 经策略验证的账号
     * @param secret 临时材料，构造时复制
     */
    public ScheduledCredentialCandidate(String accountKey, char[] secret) {
        this.accountKey = accountKey;
        this.secret = Arrays.copyOf(secret, secret.length);
    }
    /** {@return 非敏感账号键} */
    public String accountKey() { return accountKey; }
    /** {@return 临时字符副本；调用方须清零} */
    public synchronized char[] copySecret() { return Arrays.copyOf(secret, secret.length); }
    @Override public synchronized void close() { Arrays.fill(secret, '\0'); secret = new char[0]; }
    @Override public String toString() { return "ScheduledCredentialCandidate[redacted]"; }
}
