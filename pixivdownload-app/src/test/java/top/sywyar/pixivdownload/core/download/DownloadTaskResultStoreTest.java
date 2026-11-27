package top.sywyar.pixivdownload.core.download;

import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;

import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskProgress;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.core.download.web.DownloadTasksController;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import top.sywyar.pixivdownload.testsupport.HostTestMessages;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("下载执行终态持久化恢复")
class DownloadTaskResultStoreTest {
    @TempDir Path directory;
    private final RequestOwnerIdentity admin = RequestOwnerIdentity.adminScope();
    private final MutableClock clock = new MutableClock();

    private final java.util.List<DownloadTaskResultStore> stores = new java.util.ArrayList<>();

    private DownloadTaskResultStore track(DownloadTaskResultStore store) {
        stores.add(store);
        return store;
    }

    @org.junit.jupiter.api.AfterEach void closeStores() {
        stores.forEach(DownloadTaskResultStore::close);
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2020-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void expire() { now = now.plus(DownloadTaskRegistry.RETENTION).plusSeconds(1); }
    }

    private DownloadAttempt attempt() { return new DownloadAttempt(UUID.randomUUID(), "example", "opaque/作品"); }

    @Test void pagesPersistedResultsAcrossRestartWithStableTiesAndOwnerIsolation() throws Exception {
        var store = track(new DownloadTaskResultStore(directory));
        var timestamp = Instant.parse("2026-01-01T00:00:00Z");
        for (int value = 1; value <= 5; value++) {
            var id = new UUID(0, value);
            var task = new top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot(
                    new DownloadAttempt(id, "example", Integer.toString(value)), "example", "fixture",
                    DownloadEvent.Phase.COMPLETED, timestamp, value);
            store.save(task, value == 4 ? "other" : "mine");
        }
        store.close();
        var restarted = track(spy(new DownloadTaskResultStore(directory)));
        var owner = RequestOwnerIdentity.owner("mine");
        var first = restarted.history(null, 2, owner);
        assertThat(first.tasks()).extracting(task -> task.attempt().workId()).containsExactly("5", "3");
        verify(restarted, times(3)).openReceipt(any());
        verify(restarted, never()).openReceipt(new UUID(0, 4));
        clearInvocations(restarted);
        var second = restarted.history(first.next(), 2, owner);
        verify(restarted, times(2)).openReceipt(any());
        assertThat(second.tasks()).extracting(task -> task.attempt().workId()).containsExactly("2", "1");
        assertThat(second.next()).isNull();
        assertThat(restarted.history(null, 200, admin).tasks()).hasSize(5);
        assertThatThrownBy(() -> restarted.history(null, 201, admin)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(resultPath(new DownloadAttempt(new UUID(0, 1), "example", "1")), "invalid");
        Files.writeString(resultPath(new DownloadAttempt(new UUID(0, 4), "example", "4")), "invalid");
        clearInvocations(restarted);
        assertThat(restarted.history(null, 200, owner).tasks()).extracting(task -> task.attempt().workId())
                .containsExactly("5", "3", "2");
        verify(restarted, never()).openReceipt(new UUID(0, 4));
        assertThat(restarted.history(null, 200, admin).tasks()).hasSize(3);
        assertThat(restarted.find(new UUID(0, 4), owner)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void isolatesFaultyShardAndRetriesAfterRepair(boolean directoryLink) throws Exception {
        var mine = RequestOwnerIdentity.owner("mine");
        var legacyId = UUID.fromString("bb000000-0000-0000-0000-000000000007");
        var legacy = new DownloadTaskSnapshot(new DownloadAttempt(legacyId, "example", "legacy"),
                "example", "fixture", DownloadEvent.Phase.COMPLETED, Instant.EPOCH, 0);
        Path outside = directory.resolve("outside");
        try (var seed = new DownloadTaskResultStore(directory);
             var legacySource = new DownloadTaskResultStore(outside)) {
            seed.save(completed(1), "mine");
            seed.save(completed(2), "mine");
            seed.save(completed(3), "other");
            legacySource.save(legacy, "mine");
        }
        Path shard = directory.resolve("bb");
        Path source = outside.resolve("bb");
        if (!directoryLink) Files.writeString(shard, "unexpected file");
        else if (System.getProperty("os.name").startsWith("Windows")) {
            var junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", shard.toString(), source.toString()).start();
            assertThat(junction.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(junction.exitValue()).isZero();
        } else Files.createSymbolicLink(shard, source);
        try (var reader = spy(new DownloadTaskResultStore(directory))) {
            for (var identity : java.util.List.of(mine, admin)) {
                var first = reader.history(null, 1, identity);
                assertThat(first.tasks()).extracting(task -> task.attempt().attemptId())
                        .containsExactly(new UUID(0, identity.admin() ? 3 : 2));
                var rest = reader.history(first.next(), 5, identity);
                assertThat(rest.tasks()).hasSize(identity.admin() ? 2 : 1);
                assertThat(rest.next()).isNull();
                assertThat(reader.find(new UUID(0, 1), identity)).isPresent();
            }
            verify(reader, never()).openReceipt(legacyId);
            if (directoryLink) {
                assertThatThrownBy(() -> reader.find(legacyId, admin)).isInstanceOf(DownloadTaskResultStore.ReceiptUnavailable.class);
                assertThat(reader.find(legacyId, mine)).isEmpty();
            }
            Files.delete(shard);
            Files.move(source, shard);
            assertThat(reader.history(null, 5, mine).tasks()).extracting(task -> task.attempt().attemptId())
                    .containsExactly(legacyId, new UUID(0, 2), new UUID(0, 1));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowLegacyReceiptAllowsCompletionAndObservationBeforeScanResumes(boolean reopen) throws Exception {
        var old = completed(1);
        var fresh = completed(2);
        try (var seed = new DownloadTaskResultStore(directory)) {
            seed.save(old, "first");
        }
        Files.delete(directory.resolve("history.mv"));
        var reader = track(spy(new DownloadTaskResultStore(directory)));
        var writer = reopen ? track(new DownloadTaskResultStore(directory)) : reader;
        var lifecycle = new DownloadLifecycleRegistry(new DownloadTaskRegistry(clock, writer), () -> true);
        var observed = new CountDownLatch(1);
        var event = new DownloadEvent(fresh.attempt(), DownloadEvent.Phase.COMPLETED);
        lifecycle.register(new top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner(
                "observer", "observer", 1, 1), new DownloadLifecycleRegistry.Contribution(
                java.util.List.of(value -> { if (value.equals(event)) observed.countDown(); }), java.util.List.of()));
        lifecycle.register(fresh.attempt(), "second", "example", "");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(reader).openReceipt(old.attempt().attemptId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var scanning = workers.submit(() -> reader.history(null, 10, admin));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var publishing = workers.submit(() -> lifecycle.publish(event));
                assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
                publishing.get(5, TimeUnit.SECONDS);
                assertThat(scanning.isDone()).isFalse();
                assertThat(writer.find(fresh.attempt().attemptId(), RequestOwnerIdentity.owner("second"))).isPresent();
                if (reopen) {
                    workers.submit(() -> { reader.close(); writer.close(); }).get(5, TimeUnit.SECONDS);
                    try (var restarted = new DownloadTaskResultStore(directory)) {
                        assertThat(restarted.history(null, 10, admin).tasks()).hasSize(2);
                    }
                }
            } finally { release.countDown(); }
            if (reopen) assertThatThrownBy(() -> scanning.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DownloadTaskResultStore.ReceiptUnavailable.class);
            else assertThat(scanning.get(5, TimeUnit.SECONDS).tasks()).hasSize(2);
        }
        try (var restarted = new DownloadTaskResultStore(directory)) {
            for (var identity : java.util.List.of("first", "second")) {
                var owner = RequestOwnerIdentity.owner(identity);
                var expected = identity.equals("first") ? old : fresh;
                assertThat(restarted.find(expected.attempt().attemptId(), owner)).isPresent();
                assertThat(restarted.history(null, 5, owner).tasks()).extracting(task -> task.attempt().attemptId())
                        .containsExactly(expected.attempt().attemptId());
            }
        }
    }

    @Test void receiptPublishedDuringCloseInvalidatesTheReopenedReader() throws Exception {
        var writer = track(new DownloadTaskResultStore(directory));
        writer.save(completed(1), "first");
        var fresh = completed(2);
        Path target = resultPath(fresh.attempt());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor();
             var reopened = new DownloadTaskResultStore(directory)) {
            var publication = worker.submit(() -> {
                try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
                    files.when(() -> Files.move(any(Path.class), eq(target), eq(java.nio.file.StandardCopyOption.ATOMIC_MOVE),
                            eq(java.nio.file.StandardCopyOption.REPLACE_EXISTING))).thenAnswer(call -> {
                        entered.countDown();
                        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                        return call.callRealMethod();
                    });
                    writer.save(fresh, "second");
                }
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                writer.close();
                assertThat(reopened.history(null, 10, admin).tasks()).hasSize(1);
            } finally { release.countDown(); }
            publication.get(5, TimeUnit.SECONDS);
            assertThat(reopened.history(null, 10, RequestOwnerIdentity.owner("second")).tasks())
                    .extracting(task -> task.attempt().attemptId()).containsExactly(fresh.attempt().attemptId());
        }
    }

    @Test void indexesOldShardsOnceIncludingTiesAcrossDays() throws Exception {
        var old = track(new DownloadTaskResultStore(directory.resolve("original")));
        Path migrated = directory.resolve("legacy");
        Instant midnight = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 32; i++) {
            UUID id = new UUID(i == 31 ? Long.MIN_VALUE : 0, i);
            var attempt = new DownloadAttempt(id, "example", Integer.toString(i));
            old.save(new top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot(attempt,
                    "example", "fixture", DownloadEvent.Phase.COMPLETED, i < 30 ? midnight.minusNanos(1) : midnight, 1), "mine");
            Path relative = directory.relativize(resultPath(attempt));
            Files.createDirectories(migrated.resolve(relative).getParent());
            Files.copy(directory.resolve("original").resolve(relative), migrated.resolve(relative));
        }
        var owner = RequestOwnerIdentity.owner("mine");
        Path damaged = migrated.resolve("00").resolve(new UUID(0, 1) + ".json");
        byte[] valid = Files.readAllBytes(damaged);
        Files.writeString(damaged, "broken legacy record");
        DownloadTasks.HistoryPage first;
        try (var initial = spy(new DownloadTaskResultStore(migrated))) {
            first = initial.history(null, 2, owner);
            assertThat(first.tasks()).extracting(task -> task.attempt().workId()).containsExactly("31", "30");
            verify(initial, times(32 + 3)).openReceipt(any());
        }
        for (boolean repaired : new boolean[] {false, true}) {
            if (repaired) Files.write(damaged, valid);
            try (var reopened = spy(new DownloadTaskResultStore(migrated))) {
                assertThat(reopened.history(null, 1, owner).tasks()).hasSize(1);
                verify(reopened).openReceipt(new UUID(0, 1));
                verify(reopened, times(1 + 2)).openReceipt(any());
                assertThat(reopened.history(null, 200, owner).tasks()).hasSize(repaired ? 32 : 31);
            }
        }
        var restarted = track(spy(new DownloadTaskResultStore(migrated)));
        assertThat(restarted.history(first.next(), 2, owner).tasks()).extracting(task -> task.attempt().workId())
                .containsExactly("29", "28");
        verify(restarted, times(3)).openReceipt(any());
        verify(restarted, never()).openReceipt(new UUID(0, 1));
    }

    @Test void newReaderCanRecoverRepairedLegacyReceiptWhileAnotherReaderRemainsOpen() throws Exception {
        var task = completed(1);
        try (var original = new DownloadTaskResultStore(directory)) {
            original.save(task, "mine");
        }
        Files.delete(directory.resolve("history.mv"));
        Path path = resultPath(task.attempt());
        byte[] receipt = Files.readAllBytes(path);
        Files.writeString(path, "broken legacy record");
        var mine = RequestOwnerIdentity.owner("mine");
        try (var first = new DownloadTaskResultStore(directory)) {
            assertThat(first.history(null, 1, mine).tasks()).isEmpty();
            Files.write(path, receipt);
            try (var second = new DownloadTaskResultStore(directory)) {
                assertThat(second.history(null, 1, mine).tasks()).extracting(value -> value.attempt().attemptId())
                        .containsExactly(task.attempt().attemptId());
                assertThat(first.history(null, 1, mine).tasks()).hasSize(1);
            }
        }
    }

    @Test void sameReaderRecoversTransientReceiptOnRefreshWithoutChangingTheCurrentCursor() throws Exception {
        var owner = RequestOwnerIdentity.owner("mine");
        var missing = new UUID(0, 3);
        try (var original = new DownloadTaskResultStore(directory)) {
            for (int i = 1; i <= 4; i++) original.save(completed(i), i == 4 ? "other" : "mine");
        }
        Files.delete(directory.resolve("history.mv"));
        try (var reader = spy(new DownloadTaskResultStore(directory))) {
            doThrow(new IOException("transient read")).doCallRealMethod().when(reader).openReceipt(missing);
            var first = reader.history(null, 1, owner);
            assertThat(first.tasks()).extracting(t -> t.attempt().attemptId()).containsExactly(new UUID(0, 2));
            assertThat(first.next()).isNotNull();
            assertThat(reader.find(missing, owner)).isPresent();
            var continuation = reader.history(first.next(), 10, owner);
            assertThat(continuation.tasks()).extracting(t -> t.attempt().attemptId()).containsExactly(new UUID(0, 1));
            assertThat(continuation.next()).isNull();
            verify(reader, times(2)).openReceipt(missing);

            var refreshed = reader.history(null, 1, owner);
            assertThat(refreshed.tasks()).extracting(t -> t.attempt().attemptId()).containsExactly(missing);
            assertThat(reader.history(refreshed.next(), 10, owner).tasks()).extracting(t -> t.attempt().attemptId())
                    .containsExactly(new UUID(0, 2), new UUID(0, 1));
            assertThat(reader.history(null, 10, RequestOwnerIdentity.owner("other")).tasks())
                    .extracting(t -> t.attempt().attemptId()).containsExactly(new UUID(0, 4));
        }
    }

    @Test void lastingCorruptionRetriesOneShardPerRefreshThenWaitsBeforeReadingAgain() throws Exception {
        var damaged = java.util.List.of(new UUID(0, 1), new UUID(Long.MIN_VALUE, 2));
        var valid = new java.util.HashMap<UUID, byte[]>();
        try (var original = new DownloadTaskResultStore(directory)) {
            original.save(completed(3), "healthy");
            for (var id : damaged) {
                var task = new DownloadTaskSnapshot(new DownloadAttempt(id, "example", "old"), "example", "",
                        DownloadEvent.Phase.COMPLETED, Instant.EPOCH, 0);
                original.save(task, "repaired");
                Path file = resultPath(task.attempt());
                valid.put(id, Files.readAllBytes(file));
                Files.writeString(file, "damaged");
            }
        }
        Files.delete(directory.resolve("history.mv"));
        var healthy = RequestOwnerIdentity.owner("healthy");
        try (var reader = spy(new DownloadTaskResultStore(directory, clock))) {
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> { reads.incrementAndGet(); return call.callRealMethod(); })
                    .when(reader).openReceipt(argThat(damaged::contains));
            assertThat(reader.history(null, 5, healthy).tasks()).hasSize(1);
            assertThat(reads).hasValue(2);
            assertThat(reader.history(null, 5, healthy).tasks()).hasSize(1);
            assertThat(reads).hasValue(3);
            for (int i = 0; i < 20; i++) assertThat(reader.history(null, 5, healthy).tasks()).hasSize(1);
            assertThat(reads).hasValue(4);

            for (var id : damaged) Files.write(resultPath(new DownloadAttempt(id, "example", "old")), valid.get(id));
            clock.now = clock.now.plusSeconds(299);
            assertThat(reader.history(null, 5, healthy).tasks()).hasSize(1);
            assertThat(reads).hasValue(4);
            clock.now = clock.now.plusSeconds(1);
            assertThat(reader.history(null, 5, healthy).tasks()).hasSize(1);
            assertThat(reader.history(null, 5, healthy).tasks()).hasSize(1);
            assertThat(reads).hasValue(6);
            assertThat(reader.history(null, 5, RequestOwnerIdentity.owner("repaired")).tasks())
                    .extracting(t -> t.attempt().attemptId()).containsExactly(damaged.get(1), damaged.get(0));
        }
    }

    @Test void cursorSeeksWithinDenseHourWithoutWalkingPriorKeys() throws Exception {
        var store = track(spy(new DownloadTaskResultStore(directory)));
        var owner = RequestOwnerIdentity.owner("mine");
        var timestamp = Instant.parse("2026-01-01T11:11:11Z");
        final int total = 192;
        for (int i = 0; i < total; i++) {
            var attempt = new DownloadAttempt(new UUID(0, i), "example", Integer.toString(i));
            store.save(new top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot(
                    attempt, "example", "", DownloadEvent.Phase.COMPLETED, timestamp, 0), "mine");
        }
        store.close();
        var paging = track(spy(new DownloadTaskResultStore(directory)));
        var visits = new java.util.concurrent.atomic.AtomicInteger();
        var reader = track(new DownloadTaskResultStore(directory));
        doAnswer(call -> {
            java.util.Iterator<String> cursor = call.getArgument(0);
            var counted = new java.util.Iterator<String>() {
                public boolean hasNext() { return cursor.hasNext(); }
                public String next() { visits.incrementAndGet(); return cursor.next(); }
            };
            return reader.readIndexKeys(counted, call.getArgument(1), call.getArgument(2));
        }).when(paging).readIndexKeys(any(), any(), anyInt());
        DownloadTasks.HistoryCursor before = null;
        for (int i = total - 1; i >= 0; i--) {
            var page = paging.history(before, 1, owner);
            assertThat(page.tasks()).extracting(t -> t.attempt().attemptId()).containsExactly(new UUID(0, i));
            before = page.next();
            if (i > 0) assertThat(before).isNotNull();
            if (i == total - 1) assertThat(visits.get()).isEqualTo(2);
        }
        assertThat(before).isNull();
        assertThat(visits.get()).isEqualTo(3 * total - 2);
        verify(paging).openIndex(true);
        verify(paging, never()).openIndex(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"corrupt", "truncated", "empty", "older", "unavailable"})
    void publishesReceiptsAndRecoversOwnerPagesAcrossIndexFaults(String fault) throws Exception {
        var index = directory.resolve("history.mv");
        var mine = RequestOwnerIdentity.owner("mine");
        var other = RequestOwnerIdentity.owner("other");
        try (var original = new DownloadTaskResultStore(directory)) {
            original.save(completed(1), "mine");
        }
        byte[] firstIndex = Files.readAllBytes(index);
        if (fault.equals("corrupt")) Files.write(index, new byte[firstIndex.length]);
        if (fault.equals("truncated")) Files.writeString(index, "damaged index");
        if (fault.equals("empty")) Files.write(index, new byte[0]);
        if (fault.equals("unavailable")) {
            Files.delete(index);
            Files.createDirectory(index);
        }
        try (var interrupted = new DownloadTaskResultStore(directory)) {
            interrupted.save(completed(2), "other");
            interrupted.save(completed(3), "mine");
            assertThat(Files.isRegularFile(resultPath(completed(3).attempt()))).isTrue();
            assertThat(interrupted.find(new UUID(0, 3), mine)).isPresent();
            assertThat(interrupted.find(new UUID(0, 2), mine)).isEmpty();
            if (fault.equals("unavailable"))
                assertThatThrownBy(() -> interrupted.history(null, 1, mine))
                        .isInstanceOf(DownloadTaskResultStore.ReceiptUnavailable.class);
        }
        if (fault.equals("older")) Files.write(index, firstIndex);
        if (fault.equals("empty")) Files.write(index, new byte[0]);
        if (fault.equals("unavailable")) {
            Files.delete(index);
            Files.write(index, firstIndex);
        }
        for (int reopen = 0; reopen < 2; reopen++) {
            try (var recovered = new DownloadTaskResultStore(directory)) {
                var first = recovered.history(null, 1, mine);
                assertThat(first.tasks()).extracting(task -> task.attempt().attemptId()).containsExactly(new UUID(0, 3));
                assertThat(first.next()).isNotNull();
                var last = recovered.history(first.next(), 1, mine);
                assertThat(last.tasks()).extracting(task -> task.attempt().attemptId()).containsExactly(new UUID(0, 1));
                assertThat(last.next()).isNull();
                assertThat(recovered.history(null, 10, other).tasks()).extracting(task -> task.attempt().attemptId())
                        .containsExactly(new UUID(0, 2));
                assertThat(recovered.history(null, 10, admin).tasks()).extracting(task -> task.attempt().attemptId())
                        .containsExactly(new UUID(0, 3), new UUID(0, 2), new UUID(0, 1));
                assertThat(recovered.find(new UUID(0, 3), mine)).isPresent();
                assertThat(recovered.find(new UUID(0, 2), mine)).isEmpty();
            }
        }
    }

    @Test void recoversReceiptWhenWriterExitsBetweenPublicationAndIndexing() throws Exception {
        try (var original = new DownloadTaskResultStore(directory)) {
            original.save(completed(1), "other");
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path args = directory.resolve("java.args");
        Files.writeString(args, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        Path log = directory.resolve("writer.log");
        Process writer = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + args, InterruptedWriter.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(writer.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(writer.exitValue()).as("writer output: %s", Files.readString(log)).isEqualTo(71);
        } finally {
            if (writer.isAlive()) writer.destroyForcibly().waitFor();
        }
        try (var reopened = new DownloadTaskResultStore(directory)) {
            var mine = RequestOwnerIdentity.owner("mine");
            assertThat(reopened.find(new UUID(0, 2), mine)).isPresent();
            assertThat(reopened.history(null, 2, mine).tasks()).extracting(task -> task.attempt().attemptId())
                    .containsExactly(new UUID(0, 2));
            assertThat(reopened.history(null, 2, admin).tasks()).hasSize(2);
        }
    }

    public static class InterruptedWriter {
        public static void main(String[] args) throws Exception {
            var store = spy(new DownloadTaskResultStore(Path.of(args[0])));
            doAnswer(call -> {
                Runtime.getRuntime().halt(71);
                return null;
            }).when(store).openIndex(false);
            store.save(completed(2), "mine");
            throw new AssertionError("Expected writer termination");
        }
    }

    private static DownloadTaskSnapshot completed(int id) {
        return new DownloadTaskSnapshot(new DownloadAttempt(new UUID(0, id), "example", Integer.toString(id)),
                "example", "fixture", DownloadEvent.Phase.COMPLETED, Instant.EPOCH, 0);
    }

    @Test void failedReceiptPublicationCanRetryWithTheSameIndexKey() throws Exception {
        var store = track(new DownloadTaskResultStore(directory));
        var attempt = attempt();
        var owner = RequestOwnerIdentity.owner("mine");
        var task = new top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot(attempt,
                "example", "fixture", DownloadEvent.Phase.COMPLETED, Instant.EPOCH, 0);
        Path target = resultPath(attempt);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), eq(target), eq(java.nio.file.StandardCopyOption.ATOMIC_MOVE),
                    eq(java.nio.file.StandardCopyOption.REPLACE_EXISTING))).thenThrow(new IOException("publish failed"));
            assertThatThrownBy(() -> store.save(task, "mine")).isInstanceOf(IOException.class);
        }
        store.close();
        var restarted = track(new DownloadTaskResultStore(directory));
        assertThat(restarted.find(attempt.attemptId(), owner)).isEmpty();
        assertThat(restarted.history(null, 1, owner).tasks()).isEmpty();
        restarted.save(task, "mine");
        assertThat(restarted.history(null, 2, owner).tasks()).hasSize(1);
        assertThat(restarted.find(attempt.attemptId(), owner)).isPresent();
    }

    @Test void archivedLookupAndCancelReportOnlyConfirmedOwnerStorageFaults() throws Exception {
        var store = track(spy(new DownloadTaskResultStore(directory)));
        var mine = RequestOwnerIdentity.owner("mine");
        var other = RequestOwnerIdentity.owner("other");
        var attempt = attempt();
        var id = attempt.attemptId();
        var registry = new DownloadTaskRegistry(clock, store);
        registry.register(attempt, "mine", "example", "");
        registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        registry = new DownloadTaskRegistry(clock, store);
        assertThat(registry.find(id, mine)).isPresent();
        assertThat(registry.cancel(id, mine)).isEqualTo(DownloadTasks.CancelResult.TERMINAL);
        var restarted = registry;
        for (boolean transientFault : new boolean[] {true, false}) {
            if (transientFault) doThrow(new IOException("temporary read failure")).when(store).openReceipt(id);
            else {
                doCallRealMethod().when(store).openReceipt(id);
                assertThat(restarted.find(id, mine)).isPresent();
                Files.writeString(resultPath(attempt), "{");
            }
            assertThatThrownBy(() -> restarted.find(id, mine)).isInstanceOf(DownloadTaskResultStore.ReceiptUnavailable.class);
            assertThatThrownBy(() -> restarted.cancel(id, mine)).isInstanceOf(DownloadTaskResultStore.ReceiptUnavailable.class);
            assertThat(restarted.find(id, other)).isEmpty();
            assertThat(restarted.cancel(id, other)).isEqualTo(DownloadTasks.CancelResult.NOT_FOUND);
            var absent = UUID.randomUUID();
            assertThat(restarted.find(absent, mine)).isEmpty();
            assertThat(restarted.cancel(absent, mine)).isEqualTo(DownloadTasks.CancelResult.NOT_FOUND);
            assertThat(restarted.history(null, 1, mine).tasks()).isEmpty();
        }
    }

    @Test void damagedReceiptUsesPersistedOwnershipForHttpLookupAndCancel() throws Exception {
        var mine = new ProgramIdentity(UUID.randomUUID(), java.util.Set.of("core:downloads.read", "core:downloads.cancel"));
        var other = new ProgramIdentity(UUID.randomUUID(), mine.permissions());
        var attempt = attempt();
        try (var original = new DownloadTaskResultStore(directory)) {
            var tasks = new DownloadTaskRegistry(clock, original);
            tasks.register(attempt, mine.id().toString(), "example", "fixture");
            tasks.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        }
        var path = resultPath(attempt);
        var valid = Files.readAllBytes(path);
        var id = attempt.attemptId();
        try (var reopened = new DownloadTaskResultStore(directory)) {
            var lifecycle = new DownloadLifecycleRegistry(new DownloadTaskRegistry(clock, reopened), () -> true);
            var mvc = MockMvcBuilders.standaloneSetup(new DownloadTasksController(lifecycle, request -> admin,
                    HostTestMessages.messages())).build();
            var withoutOwner = (tools.jackson.databind.node.ObjectNode) tools.jackson.databind.json.JsonMapper.shared().readTree(valid);
            withoutOwner.remove("owner");
            for (String damaged : java.util.List.of("{}", "{", "{\"owner\":\"" + other.id() + "\"}", withoutOwner.toString())) {
                Files.writeString(path, damaged);
                assertArchivedHttpError(mvc, id, mine, 503, "DOWNLOAD_TASK_RECEIPT_UNAVAILABLE");
                assertArchivedHttpError(mvc, id, null, 503, "DOWNLOAD_TASK_RECEIPT_UNAVAILABLE");
                assertArchivedHttpError(mvc, id, other, 404, "DOWNLOAD_TASK_NOT_FOUND");
            }
            var absent = UUID.randomUUID();
            assertArchivedHttpError(mvc, absent, mine, 404, "DOWNLOAD_TASK_NOT_FOUND");
            assertArchivedHttpError(mvc, absent, null, 404, "DOWNLOAD_TASK_NOT_FOUND");
            Files.write(path, valid);
            mvc.perform(get("/api/download/tasks/{id}", id).requestAttr(ProgramIdentity.REQUEST_ATTRIBUTE, mine))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("COMPLETED"));
            mvc.perform(post("/api/download/tasks/{id}/cancel", id).requestAttr(ProgramIdentity.REQUEST_ATTRIBUTE, mine))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("TERMINAL"));
        }
    }

    private static void assertArchivedHttpError(MockMvc mvc, UUID id, ProgramIdentity program,
                                                int expectedStatus, String code) throws Exception {
        for (var request : java.util.List.of(get("/api/download/tasks/{id}", id), post("/api/download/tasks/{id}/cancel", id))) {
            if (program != null) request.requestAttr(ProgramIdentity.REQUEST_ATTRIBUTE, program);
            mvc.perform(request).andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
        }
    }

    private DownloadTaskRegistry registry() { return new DownloadTaskRegistry(clock, track(new DownloadTaskResultStore(directory))); }
    private Path resultPath(DownloadAttempt attempt) {
        String id = attempt.attemptId().toString();
        return directory.resolve(id.substring(0, 2)).resolve(id + ".json");
    }

    @Test void measuredProgressIsBoundToTheExactHandleAndSurvivesArchival() {
        var registry = registry();
        var attempt = attempt();
        var queue = new QueueTaskTracker("example");
        var task = queue.beginRunning("first");
        var otherTask = queue.beginRunning("other");
        var measurement = new DownloadTaskProgress(DownloadTaskProgress.Unit.PAGES, 2, 7);
        var owner = RequestOwnerIdentity.owner("first");
        try {
            registry.register(attempt, "first", "example", "work");
            registry.attach(attempt, task);
            registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.STARTED));
            registry.reportProgress(attempt, otherTask, measurement);
            assertThat(registry.find(attempt.attemptId(), owner).orElseThrow().progress()).isNull();
            registry.reportProgress(attempt, task, measurement);
            var live = registry.find(attempt.attemptId(), owner).orElseThrow();
            assertThat(live.progress()).isEqualTo(measurement);
            assertThat(registry.snapshot(owner).tasks()).containsExactly(live);
            assertThat(registry.snapshot(RequestOwnerIdentity.owner("other")).tasks()).isEmpty();
            registry.reportProgress(attempt, task, measurement);
            assertThat(registry.find(attempt.attemptId(), owner).orElseThrow().revision()).isEqualTo(live.revision());
            registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.CANCELLED));
            registry.reportProgress(attempt, task, new DownloadTaskProgress(DownloadTaskProgress.Unit.PAGES, 7, 7));
            assertThat(registry.find(attempt.attemptId(), owner).orElseThrow().progress()).isEqualTo(measurement);
            assertThat(registry().history(null, 1, owner).tasks()).extracting(t -> t.progress()).containsExactly(measurement);
        } finally {
            task.completeRunning();
            otherTask.completeRunning();
        }
    }

    @Test @DisplayName("Spring 实际装配启用终态存储")
    void componentWiringPersistsResults() {
        var attempt = attempt();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DownloadTaskResultStore.class, () -> track(new DownloadTaskResultStore(directory)));
            context.register(DownloadTaskRegistry.class);
            context.refresh();
            var registry = context.getBean(DownloadTaskRegistry.class);
            registry.register(attempt, null, "example", "标题");
            registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        }
        assertThat(track(new DownloadTaskResultStore(directory)).find(attempt.attemptId(), admin)).isPresent();
    }

    @ParameterizedTest @EnumSource(value = DownloadEvent.Phase.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("快照过期和进程重建后仍按执行身份找回终态，不重新导入历史任务")
    void terminalSurvivesSnapshotExpiryAndRestart(DownloadEvent.Phase phase) {
        var registry = registry();
        var attempt = attempt();
        String output = directory.resolve("作品").toString();
        registry.register(attempt, "owner", "example", "标题", output);
        registry.update(new DownloadEvent(attempt, phase));
        var expected = registry.find(attempt.attemptId(), admin).orElseThrow();
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).isEmpty();
        for (var current : new DownloadTaskRegistry[]{registry, registry()}) {
            var result = current.find(attempt.attemptId(), admin).orElseThrow();
            assertThat(result.outputPath()).isEqualTo(output);
            assertThat(current.history(null, 1, admin).tasks()).extracting(task -> task.outputPath()).containsExactly(output);
            assertThat(result).usingRecursiveComparison().ignoringFields("revision").isEqualTo(expected);
            assertThat(current.find(attempt.attemptId(), RequestOwnerIdentity.owner("owner"))).isPresent();
            assertThat(current.find(attempt.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
            assertThat(current.cancel(attempt.attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.TERMINAL);
            assertThat(current.cancel(attempt.attemptId(), RequestOwnerIdentity.owner("other")))
                    .isEqualTo(DownloadTasks.CancelResult.NOT_FOUND);
            assertThat(current.snapshot(admin).tasks()).isEmpty();
            assertThatThrownBy(() -> current.register(attempt, "owner", "example", "标题"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test @DisplayName("同一作品的新执行不借用旧执行结果，未完成任务不生成回执")
    void distinctAttemptsStayIndependent() {
        var registry = registry();
        var old = attempt();
        var next = attempt();
        registry.register(old, null, "example", "标题");
        registry.update(new DownloadEvent(old, DownloadEvent.Phase.COMPLETED));
        registry.register(next, null, "example", "标题");
        registry.update(new DownloadEvent(next, DownloadEvent.Phase.STARTED));
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).singleElement().satisfies(value -> {
            assertThat(value.attempt()).isEqualTo(next);
            assertThat(value.terminal()).isFalse();
        });
        assertThat(registry().find(next.attemptId(), admin)).isEmpty();
        assertThat(registry().find(old.attemptId(), admin)).isPresent();
        assertThat(registry().find(old.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
        assertThat(resultPath(next)).doesNotExist();
    }

    @Test @DisplayName("落盘失败保留内存终态，恢复可写后重试且不误报下载失败")
    void failedWriteRetainsResultUntilRetry() throws Exception {
        Path blocked = directory.resolve("blocked");
        var registry = new DownloadTaskRegistry(clock, track(new DownloadTaskResultStore(blocked)));
        var attempt = attempt();
        registry.register(attempt, null, "example", "标题");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        assertThat(registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED))).isTrue();
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).singleElement()
                .satisfies(value -> assertThat(value.phase()).isEqualTo(DownloadEvent.Phase.COMPLETED));
        Files.delete(blocked);
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).isEmpty();
        assertThat(track(new DownloadTaskResultStore(blocked)).find(attempt.attemptId(), admin)).isPresent();
    }

    @Test @DisplayName("终态写入缓慢时其他任务仍可登记和查询，原结果只保存一次")
    void slowSaveDoesNotBlockOtherTasks() throws Exception {
        var store = track(spy(new DownloadTaskResultStore(directory)));
        var registry = new DownloadTaskRegistry(clock, store);
        var finished = attempt();
        registry.register(finished, "owner", "example", "标题");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).save(any(), any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var saving = workers.submit(() -> registry.update(new DownloadEvent(finished, DownloadEvent.Phase.COMPLETED)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            workers.submit(() -> {
                var next = attempt();
                registry.register(next, "owner", "example", "另一个任务");
                assertThat(registry.find(finished.attemptId(), admin).orElseThrow().terminal()).isTrue();
                assertThat(registry.find(next.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
                assertThat(registry.snapshot(admin).tasks()).hasSize(2);
                assertThat(registry.cancel(next.attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.NOT_CANCELLABLE);
                assertThat(registry.update(new DownloadEvent(finished, DownloadEvent.Phase.FAILED))).isFalse();
            }).get(2, TimeUnit.SECONDS);
            release.countDown();
            assertThat(saving.get(5, TimeUnit.SECONDS)).isTrue();
            verify(store, times(1)).save(any(), any());
            assertThat(registry().find(finished.attemptId(), admin).orElseThrow().phase())
                    .isEqualTo(DownloadEvent.Phase.COMPLETED);
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"find", "cancel", "register"})
    @DisplayName("历史回执读取缓慢不阻断其他任务，并保留查询取消与重复登记语义")
    void slowArchiveReadDoesNotBlockOtherTasks(String operation) throws Exception {
        var archived = attempt();
        var previous = registry();
        previous.register(archived, "owner", "example", "标题");
        previous.update(new DownloadEvent(archived, DownloadEvent.Phase.COMPLETED));
        var store = track(spy(new DownloadTaskResultStore(directory)));
        var registry = new DownloadTaskRegistry(clock, store);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).find(eq(archived.attemptId()), any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var reading = workers.submit(() -> {
                switch (operation) {
                    case "find" -> assertThat(registry.find(archived.attemptId(), admin).orElseThrow().terminal()).isTrue();
                    case "cancel" -> assertThat(registry.cancel(archived.attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.TERMINAL);
                    case "register" -> assertThatThrownBy(() -> registry.register(archived, "owner", "example", "替换"))
                            .isInstanceOf(IllegalArgumentException.class);
                    default -> throw new AssertionError(operation);
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            workers.submit(() -> {
                var next = attempt();
                registry.register(next, "owner", "example", "另一个任务");
                assertThat(registry.find(next.attemptId(), admin)).isPresent();
                assertThat(registry.snapshot(admin).tasks()).singleElement()
                        .satisfies(value -> assertThat(value.attempt()).isEqualTo(next));
            }).get(2, TimeUnit.SECONDS);
            release.countDown();
            reading.get(5, TimeUnit.SECONDS);
            assertThat(registry.find(archived.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @DisplayName("过期重试写入缓慢时不重复保存，也不提前移除终态")
    void slowRetryRetainsResultWithoutBlockingSnapshots() throws Exception {
        var store = track(spy(new DownloadTaskResultStore(directory)));
        var registry = new DownloadTaskRegistry(clock, store);
        var attempt = attempt();
        registry.register(attempt, "owner", "example", "标题");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doThrow(new IOException("storage unavailable")).doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).save(any(), any());
        registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        clock.expire();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var retrying = workers.submit(() -> registry.snapshot(admin));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workers.submit(() -> registry.snapshot(admin)).get(2, TimeUnit.SECONDS).tasks())
                    .singleElement().satisfies(value -> assertThat(value.terminal()).isTrue());
            verify(store, times(2)).save(any(), any());
            release.countDown();
            assertThat(retrying.get(5, TimeUnit.SECONDS).tasks()).isEmpty();
            assertThat(registry().find(attempt.attemptId(), admin)).isPresent();
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @DisplayName("并发登记同一执行不能越过历史核对或替换所属者")
    void concurrentRegistrationKeepsIdentity() throws Exception {
        var store = track(spy(new DownloadTaskResultStore(directory)));
        var registry = new DownloadTaskRegistry(clock, store);
        var attempt = attempt();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contender = new CountDownLatch(1);
        var contenderThread = new AtomicReference<Thread>();
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).find(eq(attempt.attemptId()), any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var registering = workers.submit(() -> registry.register(attempt, "owner", "example", "标题"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var duplicate = workers.submit(() -> {
                contenderThread.set(Thread.currentThread());
                contender.countDown();
                assertThatThrownBy(() -> registry.register(attempt, "other", "example", "替换"))
                        .isInstanceOf(IllegalArgumentException.class);
            });
            assertThat(contender.await(5, TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> contenderThread.get().getState() == Thread.State.WAITING);
            assertThat(registry.snapshot(admin).tasks()).isEmpty();
            release.countDown();
            registering.get(5, TimeUnit.SECONDS);
            duplicate.get(5, TimeUnit.SECONDS);
            verify(store, times(1)).find(eq(attempt.attemptId()), any());
            assertThat(registry.find(attempt.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
            assertThat(registry.find(attempt.attemptId(), RequestOwnerIdentity.owner("owner"))).isPresent();
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @DisplayName("历史核对占用任务容量，失败后归还且不留下可见记录")
    void pendingRegistrationUsesAndReleasesCapacity() throws Exception {
        var store = track(spy(new DownloadTaskResultStore(directory)));
        var registry = new DownloadTaskRegistry(clock, store);
        var attempt = attempt();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            throw new UncheckedIOException(new IOException("unreadable result"));
        }).when(store).find(eq(attempt.attemptId()), any());
        var workers = Executors.newSingleThreadExecutor();
        try {
            var registering = workers.submit(() -> assertThatThrownBy(
                    () -> registry.register(attempt, "owner", "example", "标题"))
                    .isInstanceOf(UncheckedIOException.class));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < DownloadTaskRegistry.MAX_TASKS - 1; i++) {
                registry.reserve(new DownloadSubmission(UUID.randomUUID(), "example", "work", Map.of()), "", admin);
            }
            var next = new DownloadSubmission(UUID.randomUUID(), "example", "work", Map.of());
            assertThatThrownBy(() -> registry.reserve(next, "", admin)).isInstanceOfSatisfying(
                    DownloadTaskException.class, failure -> assertThat(failure.code())
                            .isEqualTo(DownloadTaskException.Code.CAPACITY_EXCEEDED));
            release.countDown();
            registering.get(5, TimeUnit.SECONDS);
            registry.reserve(next, "", admin);
            assertThat(registry.snapshot(admin).tasks()).hasSize(DownloadTaskRegistry.MAX_TASKS)
                    .noneMatch(value -> value.attempt().equals(attempt));
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void readsLongUnicodeOutputAndOlderRecordsWithoutAnOutputPath() throws IOException {
        var registry = registry();
        var attempt = attempt();
        String prefix = directory.toString() + "/";
        String output = prefix + "页".repeat(32_767 - prefix.length());
        assertThatThrownBy(() -> registry.register(attempt, "owner", "example", "title", output + "页"))
                .isInstanceOf(IllegalArgumentException.class);
        registry.register(attempt, "owner", "example", "title", output);
        registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        var owner = RequestOwnerIdentity.owner("owner");
        assertThat(registry().find(attempt.attemptId(), owner).orElseThrow().outputPath()).isEqualTo(output);

        var mapper = tools.jackson.databind.json.JsonMapper.shared();
        var record = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readAllBytes(resultPath(attempt)));
        record.remove("outputPath");
        Files.write(resultPath(attempt), mapper.writeValueAsBytes(record));
        var restored = registry().find(attempt.attemptId(), owner).orElseThrow();
        assertThat(restored.phase()).isEqualTo(DownloadEvent.Phase.COMPLETED);
        assertThat(restored.outputPath()).isNull();
    }

    @Test @DisplayName("损坏、超限、身份不符与非终态回执均不能作为成功证据")
    void invalidRecordsFailClosed() throws Exception {
        var store = track(new DownloadTaskResultStore(directory));
        var attempt = attempt();
        var registry = registry();
        registry.register(attempt, null, "example", "标题");
        registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        String valid = Files.readString(resultPath(attempt), StandardCharsets.UTF_8);
        for (String invalid : new String[]{"{", "null", "x".repeat(DownloadTaskResultStore.MAX_RECORD_BYTES + 1),
                valid.replace(attempt.attemptId().toString(), UUID.randomUUID().toString()),
                valid.replace("COMPLETED", "STARTED"), valid.replace("2020-01-01T00:00:00Z", "invalid")}) {
            Files.writeString(resultPath(attempt), invalid, StandardCharsets.UTF_8);
            assertThatThrownBy(() -> store.find(attempt.attemptId(), admin)).isInstanceOf(UncheckedIOException.class);
        }
        assertThat(store.find(UUID.randomUUID(), admin)).isEmpty();
    }
    @ParameterizedTest @EnumSource(value = DownloadEvent.FailureReason.class, names = {"CREDENTIALS_UNAVAILABLE", "OUTPUT_FILE_CONFLICT"})
    @DisplayName("终态失败原因在快照过期后可从持久回执和 HTTP 历史恢复")
    void terminalFailureReasonSurvivesRestart(DownloadEvent.FailureReason reason) throws Exception {
        var registry = registry();
        var attempt = attempt();
        registry.register(attempt, "owner", "example", "title");
        registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.FAILED, reason));
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).isEmpty();
        var reopened = registry();
        var restored = reopened.find(attempt.attemptId(), admin).orElseThrow();
        assertThat(restored.phase()).isEqualTo(DownloadEvent.Phase.FAILED);
        assertThat(restored.failureReason()).isEqualTo(reason);
        var lifecycle = new DownloadLifecycleRegistry(reopened, () -> true);
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTasksController(lifecycle, request -> admin,
                HostTestMessages.messages())).build();
        mvc.perform(get("/api/download/tasks/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].failureReason").value(reason.name()));
    }

}
