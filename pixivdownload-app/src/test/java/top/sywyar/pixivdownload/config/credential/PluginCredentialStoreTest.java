package top.sywyar.pixivdownload.config.credential;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("插件凭证存储")
class PluginCredentialStoreTest {

    private static final String OWNER = "fixture";
    private static final String KEY = "fixture.api-key";
    private static final String SECRET = "令牌=fixture-credential\n第二行\\尾";

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreConfigDirectory() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
    }

    @Test
    @DisplayName("按 owner 原子写入并仅返回该 owner 的解密值")
    void storesAndReadsOnlyOwnerScopedCredential() throws Exception {
        configureRuntimeDirectory();
        PluginCredentialStore store = store((byte) 0x31);

        store.update(OWNER, Map.of(KEY, SECRET));

        assertThat(store.readAll(OWNER)).containsEntry(KEY, SECRET);
        assertThat(store.readAll("other")).isEmpty();
        String stored = Files.readString(
                RuntimeFiles.resolvePluginCredentialPath(OWNER), StandardCharsets.UTF_8);
        assertThat(stored)
                .contains("format=" + PluginCredentialCipher.FORMAT)
                .doesNotContain(KEY, SECRET, "fixture-credential");
    }

    @Test
    @DisplayName("相同更新保持密文不变，显式清除最后字段后删除文件")
    void keepsNoOpByteIdenticalAndDeletesEmptyOwnerFile() throws Exception {
        configureRuntimeDirectory();
        PluginCredentialStore store = store((byte) 0x32);
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        store.update(OWNER, Map.of(KEY, SECRET));
        byte[] first = Files.readAllBytes(path);

        store.update(OWNER, Map.of(KEY, SECRET));

        assertThat(Files.readAllBytes(path)).isEqualTo(first);
        store.update(OWNER, Map.of(KEY, ""));
        assertThat(store.readAll(OWNER)).isEmpty();
        assertThat(path).doesNotExist();
    }

    @Test
    @DisplayName("清除后重新写入同一明文会生成新的随机密文")
    void rewritesSamePlaintextWithFreshCiphertext() throws Exception {
        configureRuntimeDirectory();
        PluginCredentialStore store = store((byte) 0x33);
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        store.update(OWNER, Map.of(KEY, SECRET));
        byte[] first = Files.readAllBytes(path);
        store.update(OWNER, Map.of(KEY, ""));

        store.update(OWNER, Map.of(KEY, SECRET));

        assertThat(Files.readAllBytes(path)).isNotEqualTo(first);
    }

    @Test
    @DisplayName("凭证文件被替换成明文后拒绝读取且保持原始字节")
    void rejectsPlaintextDowngradeWithoutRewriting() throws Exception {
        configureRuntimeDirectory();
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        Files.createDirectories(path.getParent());
        byte[] plaintext = (KEY + "=" + SECRET.replace("\\", "\\\\")
                .replace("\n", "\\n") + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(path, plaintext);
        PluginCredentialStore store = store((byte) 0x34);

        assertThatThrownBy(() -> store.readAll(OWNER))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("authenticated");
        assertThat(Files.readAllBytes(path)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("正式 keyring 可读取开源回退信封并只重写一次")
    void reencryptsOpenSourceFallbackWithProductionKey() throws Exception {
        configureRuntimeDirectory();
        byte[] fallback = repeated((byte) 0x35);
        byte[] production = repeated((byte) 0x36);
        PluginCredentialKeyMaterial openSource =
                PluginCredentialKeyMaterial.forTesting(fallback, fallback);
        PluginCredentialStore openSourceStore = new PluginCredentialStore(openSource);
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        openSourceStore.update(OWNER, Map.of(KEY, SECRET));
        byte[] fallbackEnvelope = Files.readAllBytes(path);

        PluginCredentialKeyMaterial productionMaterial =
                PluginCredentialKeyMaterial.forTesting(production, fallback);
        PluginCredentialStore productionStore = new PluginCredentialStore(productionMaterial);
        assertThat(productionStore.readAll(OWNER)).containsEntry(KEY, SECRET);
        byte[] productionEnvelope = Files.readAllBytes(path);

        assertThat(productionEnvelope).isNotEqualTo(fallbackEnvelope);
        assertThat(new String(productionEnvelope, StandardCharsets.UTF_8))
                .contains("key-id=" + productionMaterial.currentKeyId());
        productionStore.readAll(OWNER);
        assertThat(Files.readAllBytes(path)).isEqualTo(productionEnvelope);
    }

    @Test
    @DisplayName("把信封复制给另一 owner 后读取失败且不改写原始字节")
    void rejectsEnvelopeCopiedToAnotherOwner() throws Exception {
        configureRuntimeDirectory();
        PluginCredentialStore store = store((byte) 0x37);
        store.update(OWNER, Map.of(KEY, SECRET));
        Path source = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        Path copied = RuntimeFiles.resolvePluginCredentialPath("other");
        Files.copy(source, copied, StandardCopyOption.REPLACE_EXISTING);
        byte[] before = Files.readAllBytes(copied);

        assertThatThrownBy(() -> store.readAll("other"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("authentication failed");
        assertThat(Files.readAllBytes(copied)).isEqualTo(before);
    }

    @Test
    @DisplayName("密文篡改后读取失败且不把损坏文件当作空凭证")
    void rejectsTamperedCiphertextWithoutRewriting() throws Exception {
        configureRuntimeDirectory();
        PluginCredentialStore store = store((byte) 0x38);
        store.update(OWNER, Map.of(KEY, SECRET));
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        byte[] tampered = Files.readAllBytes(path);
        int index = indexOf(tampered, "ciphertext=".getBytes(StandardCharsets.UTF_8));
        tampered[index + "ciphertext=".length()] =
                tampered[index + "ciphertext=".length()] == 'A' ? (byte) 'B' : (byte) 'A';
        Files.write(path, tampered);

        assertThatThrownBy(() -> store.readAll(OWNER))
                .isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(path)).isEqualTo(tampered);
    }

    @Test
    @DisplayName("快照恢复保留认证信封的精确字节")
    void restoresExactEncryptedSnapshot() throws Exception {
        configureRuntimeDirectory();
        PluginCredentialStore store = store((byte) 0x39);
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        store.update(OWNER, Map.of(KEY, SECRET));
        PluginCredentialStore.Snapshot snapshot = store.snapshot(OWNER);
        byte[] expected = Files.readAllBytes(path);
        store.update(OWNER, Map.of(KEY, "replacement"));

        store.restore(OWNER, snapshot);

        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
        assertThat(store.readAll(OWNER)).containsEntry(KEY, SECRET);
    }

    @Test
    @DisplayName("旧密钥信封自动重加密后的回读验证失败会恢复原始字节")
    void restoresFallbackEnvelopeWhenAutomaticRewriteVerificationFails() throws Exception {
        configureRuntimeDirectory();
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        Files.createDirectories(path.getParent());
        byte[] fallbackEnvelope = "fallback-envelope".getBytes(StandardCharsets.UTF_8);
        Files.write(path, fallbackEnvelope);
        PluginCredentialCipher cipher = mock(PluginCredentialCipher.class);
        when(cipher.decode(eq(OWNER), any(byte[].class)))
                .thenReturn(new PluginCredentialCipher.Decoded(
                        Map.of(KEY, "legacy-secret"), true))
                .thenThrow(new IOException("simulated verification failure"));
        when(cipher.encrypt(eq(OWNER), eq(Map.of(KEY, "legacy-secret"))))
                .thenReturn("replacement-envelope".getBytes(StandardCharsets.UTF_8));
        PluginCredentialStore store = new PluginCredentialStore(cipher);

        assertThatThrownBy(() -> store.readAll(OWNER))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated verification failure");
        assertThat(Files.readAllBytes(path)).isEqualTo(fallbackEnvelope);
    }

    @Test
    @DisplayName("更新后回读验证失败会恢复既有文件且不会留下新文件")
    void restoresPreviousStateWhenUpdateVerificationFails() throws Exception {
        configureRuntimeDirectory();
        Path path = RuntimeFiles.resolvePluginCredentialPath(OWNER);
        Files.createDirectories(path.getParent());
        byte[] previous = "existing-envelope".getBytes(StandardCharsets.UTF_8);
        Files.write(path, previous);
        PluginCredentialCipher existingCipher = mock(PluginCredentialCipher.class);
        when(existingCipher.decode(eq(OWNER), any(byte[].class)))
                .thenReturn(new PluginCredentialCipher.Decoded(
                        Map.of(KEY, "old-secret"), false))
                .thenThrow(new IOException("simulated verification failure"));
        when(existingCipher.encrypt(eq(OWNER), eq(Map.of(KEY, "new-secret"))))
                .thenReturn("new-envelope".getBytes(StandardCharsets.UTF_8));
        PluginCredentialStore existingStore = new PluginCredentialStore(existingCipher);

        assertThatThrownBy(() -> existingStore.update(OWNER, Map.of(KEY, "new-secret")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated verification failure");
        assertThat(Files.readAllBytes(path)).isEqualTo(previous);

        Files.delete(path);
        PluginCredentialCipher newCipher = mock(PluginCredentialCipher.class);
        when(newCipher.encrypt(eq(OWNER), eq(Map.of(KEY, "new-secret"))))
                .thenReturn("new-envelope".getBytes(StandardCharsets.UTF_8));
        when(newCipher.decode(eq(OWNER), any(byte[].class)))
                .thenThrow(new IOException("simulated verification failure"));
        PluginCredentialStore newStore = new PluginCredentialStore(newCipher);

        assertThatThrownBy(() -> newStore.update(OWNER, Map.of(KEY, "new-secret")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated verification failure");
        assertThat(path).doesNotExist();
    }

    @Test
    @DisplayName("规范化后重复的凭证键应拒绝且不创建凭证文件")
    void rejectsDuplicateNormalizedCredentialKeys() {
        configureRuntimeDirectory();
        Map<String, String> updates = new java.util.LinkedHashMap<>();
        updates.put(KEY, "first-secret");
        updates.put(" " + KEY + " ", "second-secret");

        assertThatThrownBy(() -> store((byte) 0x3A).update(OWNER, updates))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Duplicate normalized");
        assertThat(RuntimeFiles.resolvePluginCredentialPath(OWNER)).doesNotExist();
    }

    @Test
    @DisplayName("两个存储实例并发更新同一 owner 时合并字段且不丢失")
    void mergesConcurrentUpdatesAcrossStoreInstances() throws Exception {
        configureRuntimeDirectory();
        String firstKey = "fixture.first-token";
        String secondKey = "fixture.second-token";
        CountDownLatch firstEncryptEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstEncrypt = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        CountDownLatch secondEncryptEntered = new CountDownLatch(1);
        AtomicReference<Thread> secondUpdateThread = new AtomicReference<>();
        PluginCredentialCipher firstCipher = cipher((byte) 0x3A);
        PluginCredentialCipher firstBlockingCipher = mock(PluginCredentialCipher.class);
        when(firstBlockingCipher.decode(any(String.class), any(byte[].class)))
                .thenAnswer(invocation -> firstCipher.decode(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(firstBlockingCipher.encrypt(any(String.class), anyMap()))
                .thenAnswer(invocation -> {
                    firstEncryptEntered.countDown();
                    awaitRelease(releaseFirstEncrypt);
                    return firstCipher.encrypt(
                            invocation.getArgument(0), invocation.getArgument(1));
                });
        PluginCredentialCipher secondCipher = cipher((byte) 0x3A);
        PluginCredentialCipher secondObservedCipher = mock(PluginCredentialCipher.class);
        when(secondObservedCipher.decode(any(String.class), any(byte[].class)))
                .thenAnswer(invocation -> secondCipher.decode(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(secondObservedCipher.encrypt(any(String.class), anyMap()))
                .thenAnswer(invocation -> {
                    secondEncryptEntered.countDown();
                    return secondCipher.encrypt(
                            invocation.getArgument(0), invocation.getArgument(1));
                });
        PluginCredentialStore firstStore = new PluginCredentialStore(firstBlockingCipher);
        PluginCredentialStore secondStore = new PluginCredentialStore(secondObservedCipher);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> firstUpdate = executor.submit(() -> {
                firstStore.update(OWNER, Map.of(firstKey, "first-value"));
                return null;
            });
            assertThat(firstEncryptEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Void> secondUpdate = executor.submit(() -> {
                secondUpdateThread.set(Thread.currentThread());
                secondCallStarted.countDown();
                secondStore.update(OWNER, Map.of(secondKey, "second-value"));
                return null;
            });
            assertThat(secondCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlockedStoreUpdate(secondUpdateThread.get());
            assertThat(secondEncryptEntered.getCount()).isEqualTo(1);

            releaseFirstEncrypt.countDown();
            firstUpdate.get(5, TimeUnit.SECONDS);
            assertThat(secondEncryptEntered.await(5, TimeUnit.SECONDS)).isTrue();
            secondUpdate.get(5, TimeUnit.SECONDS);

            assertThat(store((byte) 0x3A).readAll(OWNER))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            firstKey, "first-value",
                            secondKey, "second-value"));
        } finally {
            releaseFirstEncrypt.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("一个实例验证失败回滚时不会覆盖另一实例的成功更新")
    void failedRollbackDoesNotOverwriteAnotherInstanceUpdate() throws Exception {
        configureRuntimeDirectory();
        String existingKey = "fixture.existing-token";
        String failedKey = "fixture.failed-token";
        String successfulKey = "fixture.successful-token";
        PluginCredentialStore initialStore = store((byte) 0x3B);
        initialStore.update(OWNER, Map.of(existingKey, "existing-value"));

        CountDownLatch failedVerificationEntered = new CountDownLatch(1);
        CountDownLatch releaseFailedVerification = new CountDownLatch(1);
        CountDownLatch successfulCallStarted = new CountDownLatch(1);
        CountDownLatch successfulDecodeEntered = new CountDownLatch(1);
        AtomicReference<Thread> successfulUpdateThread = new AtomicReference<>();
        PluginCredentialCipher failingDelegate = cipher((byte) 0x3B);
        PluginCredentialCipher failingCipher = mock(PluginCredentialCipher.class);
        AtomicInteger failingDecodeCalls = new AtomicInteger();
        when(failingCipher.decode(any(String.class), any(byte[].class)))
                .thenAnswer(invocation -> {
                    if (failingDecodeCalls.incrementAndGet() == 2) {
                        failedVerificationEntered.countDown();
                        awaitRelease(releaseFailedVerification);
                        throw new IOException("simulated verification failure");
                    }
                    return failingDelegate.decode(
                            invocation.getArgument(0), invocation.getArgument(1));
                });
        when(failingCipher.encrypt(any(String.class), anyMap()))
                .thenAnswer(invocation -> failingDelegate.encrypt(
                        invocation.getArgument(0), invocation.getArgument(1)));

        PluginCredentialCipher successfulDelegate = cipher((byte) 0x3B);
        PluginCredentialCipher successfulCipher = mock(PluginCredentialCipher.class);
        when(successfulCipher.decode(any(String.class), any(byte[].class)))
                .thenAnswer(invocation -> {
                    successfulDecodeEntered.countDown();
                    return successfulDelegate.decode(
                            invocation.getArgument(0), invocation.getArgument(1));
                });
        when(successfulCipher.encrypt(any(String.class), anyMap()))
                .thenAnswer(invocation -> successfulDelegate.encrypt(
                        invocation.getArgument(0), invocation.getArgument(1)));

        PluginCredentialStore failingStore = new PluginCredentialStore(failingCipher);
        PluginCredentialStore successfulStore = new PluginCredentialStore(successfulCipher);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> failedUpdate = executor.submit(() -> {
                failingStore.update(OWNER, Map.of(failedKey, "failed-value"));
                return null;
            });
            assertThat(failedVerificationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Void> successfulUpdate = executor.submit(() -> {
                successfulUpdateThread.set(Thread.currentThread());
                successfulCallStarted.countDown();
                successfulStore.update(OWNER, Map.of(successfulKey, "successful-value"));
                return null;
            });
            assertThat(successfulCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlockedStoreUpdate(successfulUpdateThread.get());
            assertThat(successfulDecodeEntered.getCount()).isEqualTo(1);

            releaseFailedVerification.countDown();
            assertThatThrownBy(() -> failedUpdate.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThat(successfulDecodeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            successfulUpdate.get(5, TimeUnit.SECONDS);

            assertThat(initialStore.readAll(OWNER))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            existingKey, "existing-value",
                            successfulKey, "successful-value"))
                    .doesNotContainKey(failedKey);
        } finally {
            releaseFailedVerification.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("复合事务回滚完成前同 owner 的其它实例更新保持阻塞且随后不会丢失")
    void compoundRollbackSerializesOtherStoreInstancesUntilRollbackCompletes() throws Exception {
        configureRuntimeDirectory();
        String existingKey = "fixture.existing-token";
        String transactionKey = "fixture.transaction-token";
        String concurrentKey = "fixture.concurrent-token";
        PluginCredentialStore transactionStore = store((byte) 0x3C);
        PluginCredentialStore concurrentStore = store((byte) 0x3C);
        transactionStore.update(OWNER, Map.of(existingKey, "existing-value"));

        CountDownLatch concurrentCallStarted = new CountDownLatch(1);
        AtomicReference<Thread> concurrentThread = new AtomicReference<>();
        AtomicReference<Future<Void>> concurrentFuture = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transactionStore.withOwnerLocks(Set.of(OWNER), () -> {
                PluginCredentialStore.Snapshot snapshot = transactionStore.snapshot(OWNER);
                transactionStore.update(
                        OWNER, Map.of(transactionKey, "transaction-value"));
                concurrentFuture.set(executor.submit(() -> {
                    concurrentThread.set(Thread.currentThread());
                    concurrentCallStarted.countDown();
                    concurrentStore.update(
                            OWNER, Map.of(concurrentKey, "concurrent-value"));
                    return null;
                }));
                try {
                    assertThat(concurrentCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    awaitBlockedStoreUpdate(concurrentThread.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while observing the concurrent credential update", e);
                }
                transactionStore.restore(OWNER, snapshot);
            });

            concurrentFuture.get().get(5, TimeUnit.SECONDS);
            assertThat(transactionStore.readAll(OWNER))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            existingKey, "existing-value",
                            concurrentKey, "concurrent-value"))
                    .doesNotContainKey(transactionKey);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void configureRuntimeDirectory() {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("config").toString());
    }

    private static PluginCredentialStore store(byte keyByte) {
        return new PluginCredentialStore(cipher(keyByte));
    }

    private static PluginCredentialCipher cipher(byte keyByte) {
        byte[] key = repeated(keyByte);
        return new PluginCredentialCipher(PluginCredentialKeyMaterial.forTesting(key, key));
    }

    private static void awaitRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release the credential store operation");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to release the credential store operation", e);
        }
    }

    private static void awaitBlockedStoreUpdate(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean blockedInStoreUpdate = thread != null
                    && thread.getState() == Thread.State.BLOCKED
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                            PluginCredentialStore.class.getName().equals(frame.getClassName())
                                    && "update".equals(frame.getMethodName()));
            if (blockedInStoreUpdate) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Concurrent credential store update did not block on the owner lock");
    }

    private static byte[] repeated(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private static int indexOf(byte[] content, byte[] needle) {
        outer:
        for (int index = 0; index <= content.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (content[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        throw new IllegalArgumentException("missing byte sequence");
    }
}
