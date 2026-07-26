package top.sywyar.pixivdownload.core.schedule.capability;

import java.util.concurrent.atomic.AtomicReference;

/** 一次调度调用的共享根状态；所有 owner token 都从同一个原子状态判断存活性。 */
final class ScheduleLeaseRoot {

    enum Phase {
        PREPARED,
        PLANNING,
        EXECUTION,
        SINGLE,
        CLOSED
    }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PREPARED);
    private final ScheduleLeaseState.CancellationSignal cancellation =
            new ScheduleLeaseState.CancellationSignal();

    Phase phase() {
        return phase.get();
    }

    ScheduleLeaseState.CancellationSignal cancellation() {
        return cancellation;
    }

    boolean canActivateRoot() {
        return phase.get() == Phase.PREPARED;
    }

    boolean activatePlanning() {
        return phase.compareAndSet(Phase.PREPARED, Phase.PLANNING);
    }

    boolean activateSingle() {
        return phase.compareAndSet(Phase.PREPARED, Phase.SINGLE);
    }

    boolean transferPlanningToExecution() {
        return phase.compareAndSet(Phase.PLANNING, Phase.EXECUTION);
    }

    boolean closePlanningOrPrepared() {
        while (true) {
            Phase current = phase.get();
            if (current != Phase.PREPARED && current != Phase.PLANNING) {
                return false;
            }
            if (phase.compareAndSet(current, Phase.CLOSED)) {
                return true;
            }
        }
    }

    boolean closeSingleOrPrepared() {
        while (true) {
            Phase current = phase.get();
            if (current != Phase.PREPARED && current != Phase.SINGLE) {
                return false;
            }
            if (phase.compareAndSet(current, Phase.CLOSED)) {
                return true;
            }
        }
    }

    boolean closeExecution() {
        return phase.compareAndSet(Phase.EXECUTION, Phase.CLOSED);
    }

    boolean isPlanning() {
        return phase.get() == Phase.PLANNING;
    }

    boolean isExecution() {
        return phase.get() == Phase.EXECUTION;
    }

    boolean isSingle() {
        return phase.get() == Phase.SINGLE;
    }

    boolean isLive() {
        Phase current = phase.get();
        return current == Phase.PLANNING || current == Phase.EXECUTION || current == Phase.SINGLE;
    }

    boolean isClosed() {
        return phase.get() == Phase.CLOSED;
    }
}
