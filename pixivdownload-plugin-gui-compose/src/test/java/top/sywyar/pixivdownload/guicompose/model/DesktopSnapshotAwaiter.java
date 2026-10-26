package top.sywyar.pixivdownload.guicompose.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

final class DesktopSnapshotAwaiter {
    private DesktopSnapshotAwaiter() {}

    static DesktopUiSnapshot awaitEnabled(ComposeDesktopUiModel model, String nodeId) {
        return await(model, snapshot -> {
            var node = DesktopUiEventProtocol.index(snapshot.document()).get(nodeId);
            return node != null && node.enabled();
        });
    }

    static DesktopUiSnapshot await(ComposeDesktopUiModel model, Predicate<DesktopUiSnapshot> ready) {
        var result = new CompletableFuture<DesktopUiSnapshot>();
        try (var subscription = model.subscribeSnapshots(snapshot -> {
            try {
                if (ready.test(snapshot)) result.complete(snapshot);
            } catch (RuntimeException | AssertionError failure) {
                result.completeExceptionally(failure);
            }
        })) {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待桌面快照被中断", interrupted);
        } catch (Exception failure) {
            throw new AssertionError("桌面快照未达到预期状态", failure);
        }
    }
}
