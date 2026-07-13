package top.sywyar.pixivdownload.core.schedule.capability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 复合扩展的附加 owner 分支；失败时一次 CAS 使该分支的全部 token 同时失效。 */
final class ScheduleLeaseBranch {

    private enum Phase {
        PREPARED,
        ACTIVE,
        CLOSED
    }

    private final ScheduleLeaseRoot root;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PREPARED);

    ScheduleLeaseBranch(ScheduleLeaseRoot root) {
        this.root = Objects.requireNonNull(root, "schedule lease root");
    }

    boolean canRegister() {
        return root.isPlanning() && phase.get() == Phase.PREPARED;
    }

    boolean activate() {
        return root.isPlanning() && phase.compareAndSet(Phase.PREPARED, Phase.ACTIVE);
    }

    boolean close() {
        return phase.getAndSet(Phase.CLOSED) != Phase.CLOSED;
    }

    boolean isActive() {
        return root.isLive() && phase.get() == Phase.ACTIVE;
    }
}
