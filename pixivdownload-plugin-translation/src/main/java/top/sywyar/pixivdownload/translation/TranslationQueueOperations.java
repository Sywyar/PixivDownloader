package top.sywyar.pixivdownload.translation;

import top.sywyar.pixivdownload.plugin.api.download.queue.*;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

@PluginManagedBean
public class TranslationQueueOperations implements QueueOperations {
    private final QueueTaskTracker tracker;
    public TranslationQueueOperations(QueueTaskTracker tracker) { this.tracker = tracker; }
    public String queueType() { return "translation"; }
    public QueueDrain prepareQuiesce(String registered) {
        if (!"translation".equals(registered)) throw new IllegalArgumentException("translation.queue-owner");
        return tracker.prepareQuiesce();
    }
    public void cancelQuiescedTasks() { tracker.cancelQuiescedTasks(); }
    public int clearAll() { return tracker.cancelActive(); }
    public int clearForOwner(String owner) { return 0; }
}
