package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将宿主撤回交付给业务取消；运行中的排空仍由宿主包装器在真实退出时完成。 */
final class HistoryTask {
    private HistoryTask() {}

    static void submit(PluginRuntimeTaskRegistrar tasks, Executor executor, Runnable action,
                       Runnable cancel, Runnable cancelledBeforeStart) {
        var claimed = new AtomicBoolean();
        var task = tasks.registerOneShot(() -> {
            if (claimed.compareAndSet(false, true)) action.run();
        });
        var future = new FutureTask<Void>(task, null) {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancel.run();
                if (claimed.compareAndSet(false, true)) cancelledBeforeStart.run();
                return super.cancel(false);
            }

            @Override protected void setException(Throwable failure) {
                super.setException(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
            }
        };
        try { executor.execute(future); }
        catch (RuntimeException | Error failure) {
            task.discardUnsubmitted();
            throw failure;
        }
        task.bindCancellation(future);
    }
}
