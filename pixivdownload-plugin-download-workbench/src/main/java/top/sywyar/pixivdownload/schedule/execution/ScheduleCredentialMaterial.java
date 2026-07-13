package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;

import java.util.Arrays;

/** 宿主在复合租约内读取并在整轮结束清零的凭证材料；每次插件调用仍取得独立句柄副本。 */
final class ScheduleCredentialMaterial implements AutoCloseable {

    private char[] secret;
    private final String reference;
    private String accountKey;

    ScheduleCredentialMaterial(String secret, String reference, String accountKey) {
        this.secret = secret == null ? new char[0] : secret.toCharArray();
        this.reference = reference;
        this.accountKey = accountKey;
    }

    synchronized boolean isPresent() {
        return secret.length > 0;
    }

    synchronized void setAccountKey(String accountKey) {
        this.accountKey = accountKey;
    }

    synchronized ScheduledCredentialHandle openHandle() {
        return new Handle(Arrays.copyOf(secret, secret.length), reference, accountKey);
    }

    @Override
    public synchronized void close() {
        Arrays.fill(secret, '\0');
        secret = new char[0];
        accountKey = null;
    }

    private static final class Handle implements ScheduledCredentialHandle {

        private char[] secret;
        private final String reference;
        private final String accountKey;

        private Handle(char[] secret, String reference, String accountKey) {
            this.secret = secret;
            this.reference = reference;
            this.accountKey = accountKey;
        }

        @Override
        public synchronized boolean isPresent() {
            return secret.length > 0;
        }

        @Override
        public String reference() {
            return reference;
        }

        @Override
        public String accountKey() {
            return accountKey;
        }

        @Override
        public synchronized char[] copySecret() {
            return Arrays.copyOf(secret, secret.length);
        }

        @Override
        public synchronized void close() {
            Arrays.fill(secret, '\0');
            secret = new char[0];
        }
    }
}
