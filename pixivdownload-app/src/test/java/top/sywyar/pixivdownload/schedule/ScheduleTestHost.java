package top.sywyar.pixivdownload.schedule;

final class ScheduleTestHost {
    private ScheduleTestHost() {}
    static java.util.concurrent.CompletableFuture<Void> stopAsync(ScheduleHostLifecycle host) {
        var result = java.util.concurrent.CompletableFuture.runAsync(host::stop);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> !host.isRunning());
        return result;
    }

    static ScheduleHostLifecycle started(ScheduleRunState state) {
        var host = new ScheduleHostLifecycle(state);
        host.start();
        return host;
    }
}
